package io.archud;

import android.media.MediaDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

/**
 * Lets the player read a video that stays on a phone. A background thread reads 1 MB blocks over
 * HTTP Range (plain sockets, local-network addresses only, see NetPolicy) well ahead of the player and
 * keeps them as small temporary files in the app's private cache (the TV's Java heap is only 48 MB),
 * so playback can begin after the first 20-30 seconds are buffered, keeps going through short Wi-Fi
 * hiccups, and seeking works. Blocks far behind the player are deleted; everything is removed on close.
 */
final class RangeSource extends MediaDataSource {
    private static final int BLOCK = 1 << 20;
    /** Blocks kept ready in front of the read position (about 30-50 s of a typical phone video). */
    private static final int AHEAD = 40;
    /** Blocks fetched at the same time: several connections beat one over a busy Wi-Fi link. */
    private static final int FETCHERS = 3;
    private static final long RATE_WINDOW_MS = 8000;
    private static final int BEHIND = 4;
    private static final long WAIT_LIMIT_MS = 20_000;

    private static final class Blk {
        final File file;
        final int len;

        Blk(File file, int len) {
            this.file = file;
            this.len = len;
        }
    }

    private final InetAddress addr;
    private final int port;
    private final String path;
    private final String token;
    private final File dir;
    private final Object lock = new Object();
    private final HashMap<Long, Blk> cache = new HashMap<>();
    private final java.util.HashSet<Long> inFlight = new java.util.HashSet<>();
    private final java.util.ArrayDeque<long[]> samples = new java.util.ArrayDeque<>();
    private volatile long total = -1;
    private volatile boolean closed;
    private long lastRead;
    private long wanted = -1;
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
        this.dir = cacheDir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No room to buffer");
        }
        clearDir();
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
            int within = (int) (pos - idx * BLOCK);
            if (b == null || within >= b.len) {
                break;
            }
            int n = Math.min(size - done, b.len - within);
            try (RandomAccessFile raf = new RandomAccessFile(b.file, "r")) {
                raf.seek(within);
                raf.readFully(buffer, offset + done, n);
            }
            done += n;
        }
        synchronized (lock) {
            lastRead = position + done;
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

    /** Bytes ready in a row in front of the read position. */
    long ahead() {
        synchronized (lock) {
            return aheadLocked();
        }
    }

    /** True when everything from the read position to the end of the file is already buffered. */
    boolean restBuffered() {
        synchronized (lock) {
            long t = total;
            return t >= 0 && t - lastRead <= aheadLocked();
        }
    }

    private long aheadLocked() {
        long end = lastRead;
        long idx = lastRead / BLOCK;
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
        return Math.max(0, end - lastRead);
    }

    // ---------------------------------------------------------------- reading ahead

    /** Waits for the block holding pos, asking the fetch thread to get it first. */
    private Blk obtain(long idx, long pos) throws IOException {
        long deadline = System.currentTimeMillis() + WAIT_LIMIT_MS;
        synchronized (lock) {
            while (true) {
                if (closed) {
                    return null;
                }
                Blk b = cache.get(idx);
                if (b != null) {
                    return b;
                }
                if (failure != null) {
                    throw new IOException(failure.getMessage());
                }
                wanted = idx;
                lastRead = pos;
                lock.notifyAll();
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
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

    /** The block to fetch next (the one the player is waiting for, else the next missing one ahead). */
    private long next() {
        if (wanted >= 0 && !cache.containsKey(wanted) && !inFlight.contains(wanted)) {
            return wanted;
        }
        long base = lastRead / BLOCK;
        long t = total;
        for (long i = base; i <= base + AHEAD; i++) {
            if (t >= 0 && i * BLOCK >= t) {
                break;
            }
            if (!cache.containsKey(i) && !inFlight.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private void trim() {
        long base = lastRead / BLOCK;
        Iterator<java.util.Map.Entry<Long, Blk>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, Blk> e = it.next();
            long k = e.getKey();
            if (k < base - BEHIND || k > base + AHEAD + 2) {
                e.getValue().file.delete();
                it.remove();
            }
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
                        lock.wait();
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
                    File f = new File(dir, "b" + idx);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data, 0, got);
                    }
                    blk = new Blk(f, got);
                } catch (IOException e) {
                    error = e;
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

    private void clearDir() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("b")) {
                    f.delete();
                }
            }
        }
        synchronized (lock) {
            cache.clear();
        }
    }

    // ---------------------------------------------------------------- one ranged HTTP request

    /** One ranged GET; returns the bytes read (0 or -1 past the end) and learns the total size. */
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
            int got = 0;
            while (got < len) {
                int n = in.read(out, got, len - got);
                if (n < 0) {
                    break;
                }
                got += n;
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
