package io.archud;

import android.media.MediaDataSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lets the player read a video that stays on a phone: it asks the phone's file server for byte ranges
 * (HTTP Range) and keeps one 1 MB block in memory, so nothing is stored on the TV and seeking works.
 * Plain sockets to local-network addresses only (see NetPolicy); no internet, no cleartext HTTP stack.
 */
final class RangeSource extends MediaDataSource {
    private static final int BLOCK = 1 << 20;

    private final InetAddress addr;
    private final int port;
    private final String path;
    private final String token;
    private final byte[] block = new byte[BLOCK];
    private long blockStart = -1;
    private int blockLen;
    private volatile long total = -1;
    private volatile boolean closed;

    /** Connects once to learn the file size; throws if the phone cannot be reached or refuses. */
    RangeSource(String host, int port, String path, String token) throws IOException {
        this.addr = InetAddress.getByName(host);
        if (!NetPolicy.isLocal(addr)) {
            throw new IOException("Only local addresses are allowed");
        }
        this.port = port;
        this.path = path;
        this.token = token;
        if (fetch(0, new byte[1], 1) < 1 || total <= 0) {
            throw new IOException("The phone did not send the file size");
        }
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed || size <= 0) {
            return closed ? -1 : 0;
        }
        if (total >= 0 && position >= total) {
            return -1;
        }
        if (blockStart < 0 || position < blockStart || position >= blockStart + blockLen) {
            blockStart = -1;
            int got = fetch(position, block, BLOCK);
            if (got <= 0) {
                return -1;
            }
            blockStart = position;
            blockLen = got;
        }
        int n = Math.min(size, (int) (blockStart + blockLen - position));
        System.arraycopy(block, (int) (position - blockStart), buffer, offset, n);
        return n;
    }

    @Override
    public long getSize() {
        return total;
    }

    @Override
    public void close() {
        closed = true;
    }

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
