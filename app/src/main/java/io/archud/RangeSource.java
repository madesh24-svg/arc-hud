package io.archud;

import android.media.MediaDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the player read a video that stays on a phone. Background threads read 1 MB blocks over HTTP
 * Range (plain sockets, local-network addresses only, see NetPolicy) well ahead of the player and keep
 * them as small temporary files in the app's private cache (the TV's Java heap is only 48 MB), so
 * playback can begin after the first 20-30 seconds are buffered, keeps going through short Wi-Fi
 * hiccups, and seeking works.
 *
 * A player reads audio and video from the file at the same time, often far apart (always after a big
 * seek), so the buffer follows several read positions ("cursors") at once instead of one.
 */
final class RangeSource extends MediaDataSource {
    private static final int BLOCK = 1 << 20;
    /** Blocks kept ready in front of one read position (shared out when there are several). */
    private static final int AHEAD = 40;
    private static final int BEHIND = 4;
    /** Blocks fetched at the same time: several connections beat one over a busy Wi-Fi link. */
    private static final int FETCHERS = 3;
    private static final int MAX_CURSORS = 3;
    /** A read this close to a cursor belongs to it; further away starts another cursor. */
    private static final long SAME_CURSOR = 16L * BLOCK;
    private static final long CURSOR_IDLE_MS = 20_000;
    private static final long WAIT_LIMIT_MS = 30_000;
    private static final long RATE_WINDOW_MS = 8000;

    private static final class Blk {
        final File file;
        final int len;
        /** Readers using this block right now; its file is only deleted when this is 0 (guarded by lock). */
        int pins;
        /** Taken out of the buffer; the file goes as soon as the last reader is done. */
        boolean dead;

        Blk(File file, int len) {
            this.file = file;
            this.len = len;
        }
    }

    /** One read position: where it is, which block it is waiting for, and when it last read. */
    private static final class Cursor {
        long pos;
        long wanted = -1;
        long touched;
    }

    private final InetAddress addr;
    private final int port;
    private final String path;
    private final String token;
    private final File dir;
    private final Object lock = new Object();
    private final HashMap<Long, Blk> cache = new HashMap<>();
    private final HashSet<Long> inFlight = new HashSet<>();
    private final List<Cursor> cursors = new ArrayList<>();
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicLong fileSeq = new java.util.concurrent.atomic.AtomicLong();
    private final File logFile;
    private volatile long total = -1;
    private volatile boolean closed;
    private IOException failure;

    /** Connects once to learn the file size; throws if the phone cannot be reached or refuses. */
    RangeSource(String host, int port, String path, String token, File cacheDir) throws IOException {
        this.addr = InetAddress.getByName(host);
        if (!NetPolicy.isLocal(addr)) {
            throw new IOException("Only local addresses are allowed");
        }
        this.port = port;
        this.path = path;
        this.token = token;
        // Small event log for diagnosing stalls (app cache folder, capped); read it with root or run-as.
        this.logFile = new File(cacheDir.getParentFile(), "arcrange.log");
        if (logFile.length() > 64 * 1024) {
            logFile.delete();
        }
        // One folder per video: a late write from an earlier video can never land in this one.
        deleteTree(cacheDir);
        this.dir = new File(cacheDir, "s" + System.nanoTime());
        if (!dir.mkdirs()) {
            throw new IOException("No room to buffer");
        }
        if (fetch(0, new byte[1], 1) < 1 || total <= 0) {
            throw new IOException("The phone did not send the file size");
        }
        for (int i = 0; i < FETCHERS; i++) {
            Thread t = new Thread(this::fetchLoop, "arc-range-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------------------------------------------------------------- MediaDataSource

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) {
            return -1;
        }
        if (size <= 0) {
            return 0;
        }
        long t = total;
        if (t >= 0 && position >= t) {
            return -1;
        }
        int done = 0;
        while (done < size) {
            long pos = position + done;
            if (t >= 0 && pos >= t) {
                break;
            }
            long idx = pos / BLOCK;
            Blk b = obtain(idx, pos);
            if (b == null) {
                break;
            }
            int within = (int) (pos - idx * BLOCK);
            if (within >= b.len) {
                release(b);
                break;
            }
            int n = Math.min(size - done, b.len - within);
            try (RandomAccessFile raf = new RandomAccessFile(b.file, "r")) {
                raf.seek(within);
                raf.readFully(buffer, offset + done, n);
            } catch (IOException e) {
                note("read of " + b.file.getName() + " failed: " + e);
                throw e;
            } finally {
                release(b);
            }
            done += n;
        }
        synchronized (lock) {
            Cursor c = cursorFor(position);
            c.pos = position + done;
            c.wanted = -1;
            lock.notifyAll();
        }
        return done == 0 ? -1 : done;
    }

    @Override
    public long getSize() {
        return total;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        clearDir();
    }

    // ---------------------------------------------------------------- buffer status for the player

    /** Bytes ready in a row in front of the slowest active read position. */
    long ahead() {
        synchronized (lock) {
            return aheadLocked();
        }
    }

    /** True when everything from every active read position to the end of the file is buffered. */
    boolean restBuffered() {
        synchronized (lock) {
            long t = total;
            if (t < 0 || cursors.isEmpty()) {
                return false;
            }
            long now = System.currentTimeMillis();
            for (Cursor c : cursors) {
                if (now - c.touched <= CURSOR_IDLE_MS && !nearEnd(c) && t - c.pos > aheadOf(c)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Bytes per second received over the last few seconds (0 until there is data). */
    long rate() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            while (!samples.isEmpty() && now - samples.peekFirst()[0] > RATE_WINDOW_MS) {
                samples.removeFirst();
            }
            if (samples.isEmpty()) {
                return 0;
            }
            long sum = 0;
            for (long[] s : samples) {
                sum += s[1];
            }
            long span = Math.max(1000, now - samples.peekFirst()[0]);
            return sum * 1000 / span;
        }
    }

    private long aheadLocked() {
        long now = System.currentTimeMillis();
        long least = -1;
        for (Cursor c : cursors) {
            if (now - c.touched > CURSOR_IDLE_MS || nearEnd(c)) {
                continue;
            }
            long a = aheadOf(c);
            if (least < 0 || a < least) {
                least = a;
            }
        }
        return Math.max(0, least);
    }

    /** A read position in the last few MB is the player reading the file's index (or finishing). */
    private boolean nearEnd(Cursor c) {
        long t = total;
        return t >= 0 && t - c.pos <= 4L * BLOCK;
    }

    private long aheadOf(Cursor c) {
        long end = c.pos;
        long idx = c.pos / BLOCK;
        while (true) {
            Blk b = cache.get(idx);
            if (b == null) {
                break;
            }
            end = idx * BLOCK + b.len;
            if (b.len < BLOCK) {
                break;
            }
            idx++;
        }
        return Math.max(0, end - c.pos);
    }

    // ---------------------------------------------------------------- cursors

    /** The cursor a read at pos belongs to (a new one if none is near). Call with the lock held. */
    private Cursor cursorFor(long pos) {
        long now = System.currentTimeMillis();
        Cursor best = null;
        Iterator<Cursor> it = cursors.iterator();
        while (it.hasNext()) {
            Cursor c = it.next();
            if (now - c.touched > CURSOR_IDLE_MS) {
                it.remove();
                continue;
            }
            if (Math.abs(c.pos - pos) <= SAME_CURSOR && (best == null
                    || Math.abs(c.pos - pos) < Math.abs(best.pos - pos))) {
                best = c;
            }
        }
        if (best == null) {
            best = new Cursor();
            best.pos = pos;
            cursors.add(best);
            note("new read position at block " + pos / BLOCK + " (now " + cursors.size() + ")");
            if (cursors.size() > MAX_CURSORS) {
                Cursor oldest = cursors.get(0);
                for (Cursor c : cursors) {
                    if (c.touched < oldest.touched) {
                        oldest = c;
                    }
                }
                if (oldest != best) {
                    cursors.remove(oldest);
                }
            }
        }
        best.touched = now;
        return best;
    }

    // ---------------------------------------------------------------- reading ahead

    /** Waits for the block holding pos, asking the fetch threads to get it first. */
    private Blk obtain(long idx, long pos) throws IOException {
        long deadline = System.currentTimeMillis() + WAIT_LIMIT_MS;
        synchronized (lock) {
            while (true) {
                if (closed) {
                    return null;
                }
                Blk b = cache.get(idx);
                if (b != null) {
                    b.pins++;
                    Cursor own = cursorFor(pos);
                    own.wanted = -1;
                    return b;
                }
                if (failure != null) {
                    throw new IOException(failure.getMessage());
                }
                Cursor c = cursorFor(pos);
                c.wanted = idx;
                lock.notifyAll();
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    note("gave up waiting for block " + idx + " (cursors " + cursors.size() + ")");
                    throw new IOException("The phone is too slow or went away");
                }
                try {
                    lock.wait(Math.min(left, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }
    }

    /** Blocks each cursor may keep ahead of itself: the budget is shared between active cursors. */
    private int aheadBudget() {
        long now = System.currentTimeMillis();
        int active = 0;
        for (Cursor c : cursors) {
            if (now - c.touched <= CURSOR_IDLE_MS) {
                active++;
            }
        }
        return Math.max(8, AHEAD / Math.max(1, active));
    }

    /** The block to fetch next: one a reader is waiting for, else the next missing one ahead of a cursor. */
    private long next() {
        long t = total;
        for (Cursor c : cursors) {
            long w = c.wanted;
            if (w >= 0 && !cache.containsKey(w) && !inFlight.contains(w)) {
                return w;
            }
        }
        int budget = aheadBudget();
        long now = System.currentTimeMillis();
        for (Cursor c : cursors) {
            if (now - c.touched > CURSOR_IDLE_MS) {
                continue;
            }
            long base = c.pos / BLOCK;
            for (long i = base; i <= base + budget; i++) {
                if (t >= 0 && i * BLOCK >= t) {
                    break;
                }
                if (!cache.containsKey(i) && !inFlight.contains(i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void trim() {
        int budget = aheadBudget();
        Iterator<Map.Entry<Long, Blk>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Blk> e = it.next();
            long k = e.getKey();
            boolean keep = false;
            for (Cursor c : cursors) {
                long base = c.pos / BLOCK;
                if (k >= base - BEHIND && k <= base + budget + 2 || k == c.wanted) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                drop(e.getValue());
                it.remove();
            }
        }
    }

    /** A reader is done with a block; delete its file now if it was dropped meanwhile. */
    private void release(Blk b) {
        synchronized (lock) {
            b.pins--;
            if (b.dead && b.pins <= 0) {
                b.file.delete();
            }
        }
    }

    /** Take a block out of use (lock held): delete the file unless a reader still has it open. */
    private void drop(Blk b) {
        if (b.pins > 0) {
            b.dead = true;
        } else {
            b.file.delete();
        }
    }

    private void fetchLoop() {
        byte[] data = new byte[BLOCK];
        while (!closed) {
            long idx;
            synchronized (lock) {
                idx = next();
                while (idx < 0 && !closed) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    idx = next();
                }
                if (!closed) {
                    inFlight.add(idx);
                }
            }
            if (closed) {
                return;
            }
            Blk blk = null;
            IOException error = null;
            for (int attempt = 0; attempt < 3 && !closed && blk == null; attempt++) {
                try {
                    int got = Math.max(fetch(idx * BLOCK, data, BLOCK), 0);
                    File f = new File(dir, "b" + idx + "_" + fileSeq.incrementAndGet());
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data, 0, got);
                    }
                    blk = new Blk(f, got);
                } catch (IOException e) {
                    error = e;
                    note("fetch of block " + idx + " attempt " + attempt + " failed: " + e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
            synchronized (lock) {
                inFlight.remove(idx);
                if (blk != null) {
                    if (closed) {
                        blk.file.delete();
                    } else {
                        cache.put(idx, blk);
                        samples.add(new long[] {System.currentTimeMillis(), blk.len});
                        trim();
                    }
                } else if (!closed) {
                    failure = error;
                }
                lock.notifyAll();
            }
        }
    }

    private void note(String what) {
        try (FileWriter w = new FileWriter(logFile, true)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new java.util.Date())
                    + " " + what + "\n");
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private void clearDir() {
        synchronized (lock) {
            cache.clear();
        }
        deleteTree(dir);
    }

    /** Deletes a folder and everything in it (a missing folder is fine). */
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // ---------------------------------------------------------------- one ranged HTTP request

    /**
     * One ranged GET; returns the bytes read (-1 past the end) and learns the total size. A piece that
     * ends early (the phone or the Wi-Fi dropped it) is an error, so it is retried and never kept.
     */
    private int fetch(long start, byte[] out, int len) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), 3000);
            s.setSoTimeout(10000);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + addr.getHostAddress() + ":" + port
                    + "\r\nRange: bytes=" + start + "-" + (start + len - 1)
                    + "\r\nAuthorization: Bearer " + token
                    + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            String status = line(in);
            if (status == null) {
                throw new IOException("The phone closed the connection");
            }
            if (status.contains(" 416 ")) {
                return -1;
            }
            boolean partial = status.contains(" 206 ");
            if (!partial && !status.contains(" 200 ")) {
                throw new IOException("The phone answered: " + status);
            }
            long contentLength = -1;
            String l;
            while ((l = line(in)) != null && !l.isEmpty()) {
                String lower = l.toLowerCase(Locale.US);
                if (lower.startsWith("content-range:")) {
                    int slash = l.lastIndexOf('/');
                    if (slash > 0) {
                        total = Long.parseLong(l.substring(slash + 1).trim());
                    }
                } else if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(l.substring(15).trim());
                }
            }
            if (!partial) {
                if (start > 0) {
                    throw new IOException("The phone's file server does not support seeking");
                }
                if (total < 0) {
                    total = contentLength;
                }
            }
            long expected = total >= 0 ? Math.min(len, total - start) : len;
            int got = 0;
            while (got < len) {
                int n = in.read(out, got, len - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            if (got < expected) {
                throw new IOException("The phone sent only " + got + " of " + expected + " bytes");
            }
            return got;
        } catch (NumberFormatException e) {
            throw new IOException("Unreadable answer from the phone");
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r' && buf.size() < 4096) {
                buf.write(b);
            }
        }
        if (b < 0 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
