package io.archud.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Talks to ARC HUD on the TV over plain sockets. Refuses any address that is not on the local network,
 * so the PIN can never be sent to the internet.
 */
final class TvClient {
    static final int PORT = 8765;

    static final class Reply {
        final int code;
        final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }

        boolean ok() {
            return code == 200;
        }
    }

    private TvClient() {
    }

    /** True for a dotted IPv4 address that is private (home network). */
    static boolean isLocalIp(String host) {
        if (host == null || !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isSiteLocalAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /** POST a form to the TV; the PIN goes in a header. */
    static Reply post(String host, String path, String pin, String body) throws IOException {
        if (!isLocalIp(host)) {
            throw new IOException("The TV address must be a home-network address like 192.168.0.20");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.setSoTimeout(15000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + ":" + PORT
                    + "\r\nX-Pin: " + pin
                    + "\r\nContent-Type: application/x-www-form-urlencoded"
                    + "\r\nContent-Length: " + payload.length
                    + "\r\nConnection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            return read(s.getInputStream());
        }
    }

    /** POST raw bytes (a photo) or just headers; each extra header is a full "Name: value" line. */
    static Reply send(String host, String path, String pin, String[] headers, byte[] payload) throws IOException {
        if (!isLocalIp(host)) {
            throw new IOException("The TV address must be a home-network address like 192.168.0.20");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.setSoTimeout(20000);
            StringBuilder head = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\nHost: ")
                    .append(host).append(':').append(PORT).append("\r\nX-Pin: ").append(pin);
            for (String h : headers) {
                head.append("\r\n").append(h);
            }
            head.append("\r\nContent-Length: ").append(payload.length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            return read(s.getInputStream());
        }
    }

    /** POST a file read from a stream (a song), so it never has to sit in memory whole. */
    static Reply send(String host, String path, String pin, String[] headers, InputStream body, long length)
            throws IOException {
        if (!isLocalIp(host)) {
            throw new IOException("The TV address must be a home-network address like 192.168.0.20");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.setSoTimeout(30000);
            StringBuilder head = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\nHost: ")
                    .append(host).append(':').append(PORT).append("\r\nX-Pin: ").append(pin);
            for (String h : headers) {
                head.append("\r\n").append(h);
            }
            head.append("\r\nContent-Length: ").append(length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[32768];
            long left = length;
            while (left > 0) {
                int n = body.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("the song could not be read to the end");
                }
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
            return read(s.getInputStream());
        }
    }

    private static Reply read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while (buf.size() < 16384 && (n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        String all = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        int split = all.indexOf("\r\n\r\n");
        String head = split >= 0 ? all.substring(0, split) : all;
        String text = split >= 0 ? all.substring(split + 4) : "";
        String[] parts = head.split(" ");
        int code = 0;
        if (parts.length >= 2) {
            try {
                code = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                code = 0;
            }
        }
        return new Reply(code, text.trim());
    }

    /** Looks for the TV on this phone's /24 network (port 8765, page titled ARC LINK). Null if none. */
    static String find() {
        String prefix = ownPrefix();
        if (prefix == null) {
            return null;
        }
        final ConcurrentLinkedQueue<String> found = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                if (looksLikeTv(host)) {
                    found.add(host);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        return found.peek();
    }

    private static boolean looksLikeTv(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), 400);
            s.setSoTimeout(1500);
            s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return read(s.getInputStream()).text.contains("ARC LINK");
        } catch (IOException e) {
            return false;
        }
    }

    private static String ownPrefix() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        return null;
    }
}
