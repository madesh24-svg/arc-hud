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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A continuous video stream from the phone (MPEG transport stream) as a data source for the player.
 * The phone repackages the video and starts it at the time asked for, so seeking is just opening a new
 * PhoneStream at another time. A reader thread takes the stream in 1 MB blocks, keeps up to 24 of them
 * ready ahead of the player as small temporary files (the TV's Java heap is only 48 MB), and stops
 * reading when enough is waiting, which slows the phone through the network connection.
 * Plain sockets to local-network addresses only (see NetPolicy).
 */
final class PhoneStream extends MediaDataSource implements BufferInfo {
    private static final int BLOCK = 1 << 20;
    private static final int AHEAD = 24;
    private static final int BEHIND = 6;
    private static final long WAIT_LIMIT_MS = 30_000;
    private static final long RATE_WINDOW_MS = 8000;

    private static final class Blk {
        final File file;
        final int len;
        int pins;
        boolean dead;

        Blk(File file, int len) {
            this.file = file;
            this.len = len;
        }
    }

    private final File dir;
    private final Object lock = new Object();
    private final HashMap<Long, Blk> blocks = new HashMap<>();
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private final AtomicLong fileSeq = new AtomicLong();
    private final Socket socket;
    private final InputStream in;
    private volatile boolean closed;
    private boolean eof;
    private IOException failure;
    private long producedBlocks;
    private long producedBytes;
    private long consumerPos;
    /** The picture time the phone really started at (a key frame at or before the one asked for). */
    final long startMs;

    /** Opens the stream; throws if the phone cannot be reached or refuses. */
    PhoneStream(String host, int port, String token, long wantMs, boolean audio, File cacheDir) throws IOException {
        InetAddress addr = InetAddress.getByName(host);
        if (!NetPolicy.isLocal(addr)) {
            throw new IOException("Only local addresses are allowed");
        }
        dir = new File(cacheDir, "t" + System.nanoTime());
        if (!dir.mkdirs()) {
            throw new IOException("No room to buffer");
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(addr, port), 3000);
            s.setSoTimeout(20_000);
            String req = "GET /s?start=" + Math.max(0, wantMs) + "&audio=" + (audio ? 1 : 0) + " HTTP/1.1\r\nHost: "
                    + addr.getHostAddress() + ":" + port + "\r\nAuthorization: Bearer " + token
                    + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream stream = new BufferedInputStream(s.getInputStream(), 65536);
            String status = line(stream);
            if (status == null || !status.contains(" 200 ")) {
                throw new IOException("The phone answered: " + status);
            }
            long started = wantMs;
            String l;
            while ((l = line(stream)) != null && !l.isEmpty()) {
                if (l.toLowerCase(Locale.US).startsWith("x-start-ms:")) {
                    started = Long.parseLong(l.substring(11).trim());
                }
            }
            startMs = started;
            socket = s;
            in = stream;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
            deleteTree(dir);
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
        Thread t = new Thread(this::pump, "arc-stream");
        t.setDaemon(true);
        t.start();
    }

    /** Removes buffer folders left behind by earlier videos. */
    static void purge(File cacheDir) {
        deleteTree(cacheDir);
    }

    // ---------------------------------------------------------------- reading the phone's stream

    private void pump() {
        byte[] buf = new byte[BLOCK];
        try {
            while (!closed) {
                synchronized (lock) {
                    while (!closed && producedBlocks - consumerPos / BLOCK >= AHEAD) {
                        lock.wait(500);
                    }
                }
                if (closed) {
                    return;
                }
                int got = 0;
                while (got < BLOCK) {
                    int n = in.read(buf, got, BLOCK - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
                if (got > 0) {
                    long idx;
                    synchronized (lock) {
                        idx = producedBlocks;
                    }
                    File f = new File(dir, "p" + idx + "_" + fileSeq.incrementAndGet());
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(buf, 0, got);
                    }
                    synchronized (lock) {
                        if (closed) {
                            f.delete();
                            return;
                        }
                        blocks.put(idx, new Blk(f, got));
                        producedBlocks++;
                        producedBytes += got;
                        samples.add(new long[] {System.currentTimeMillis(), got});
                        trim();
                        lock.notifyAll();
                    }
                }
                if (got < BLOCK) {
                    synchronized (lock) {
                        eof = true;
                        lock.notifyAll();
                    }
                    return;
                }
            }
        } catch (IOException e) {
            synchronized (lock) {
                if (!closed) {
                    failure = e;
                }
                lock.notifyAll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- MediaDataSource

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (size <= 0) {
            return 0;
        }
        int done = 0;
        while (done < size) {
            long pos = position + done;
            long idx = pos / BLOCK;
            Blk b;
            long deadline = System.currentTimeMillis() + WAIT_LIMIT_MS;
            synchronized (lock) {
                while (true) {
                    if (closed) {
                        return done == 0 ? -1 : done;
                    }
                    b = blocks.get(idx);
                    if (b != null) {
                        b.pins++;
                        break;
                    }
                    if (idx < producedBlocks) {
                        throw new IOException("The stream cannot go back that far");
                    }
                    if (eof) {
                        return done == 0 ? -1 : done;
                    }
                    if (failure != null) {
                        throw new IOException(failure.getMessage());
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        throw new IOException("The phone stopped sending");
                    }
                    try {
                        lock.wait(Math.min(left, 500));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (pos > consumerPos) {
                    consumerPos = pos;
                    lock.notifyAll();
                }
            }
            int within = (int) (pos - idx * BLOCK);
            if (within >= b.len) {
                release(b);
                return done == 0 ? -1 : done;
            }
            int n = Math.min(size - done, b.len - within);
            try (RandomAccessFile raf = new RandomAccessFile(b.file, "r")) {
                raf.seek(within);
                raf.readFully(buffer, offset + done, n);
            } finally {
                release(b);
            }
            done += n;
        }
        synchronized (lock) {
            if (position + done > consumerPos) {
                consumerPos = position + done;
                lock.notifyAll();
            }
        }
        return done;
    }

    @Override
    public long getSize() {
        return -1;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
        synchronized (lock) {
            blocks.clear();
        }
        deleteTree(dir);
    }

    // ---------------------------------------------------------------- BufferInfo

    @Override
    public long ahead() {
        synchronized (lock) {
            return Math.max(0, producedBytes - consumerPos);
        }
    }

    @Override
    public boolean restBuffered() {
        synchronized (lock) {
            return eof;
        }
    }

    @Override
    public long rate() {
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

    @Override
    public long sizeBytes() {
        return -1;
    }

    // ---------------------------------------------------------------- housekeeping

    private void trim() {
        long keepFrom = consumerPos / BLOCK - BEHIND;
        Iterator<Map.Entry<Long, Blk>> it = blocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Blk> e = it.next();
            if (e.getKey() < keepFrom) {
                Blk b = e.getValue();
                if (b.pins > 0) {
                    b.dead = true;
                } else {
                    b.file.delete();
                }
                it.remove();
            }
        }
    }

    private void release(Blk b) {
        synchronized (lock) {
            b.pins--;
            if (b.dead && b.pins <= 0) {
                b.file.delete();
            }
            trim();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static String line(InputStream stream) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = stream.read()) >= 0) {
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
