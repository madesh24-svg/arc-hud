package io.archud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Network rules for the device scanner: it may only talk to addresses on the local network
 * (private, link-local, loopback). Internet access is reserved for Updater and its repo allowlist.
 * Local HTTP is done over plain sockets so the app-wide HTTPS-only policy stays in force.
 */
final class NetPolicy {
    private static final int MAX_RESPONSE = 128 * 1024;

    private NetPolicy() {
    }

    static boolean isLocal(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress();
    }

    /** GET (body == null) or SOAP POST to a local device; refuses anything that is not on the LAN. */
    static String localHttp(String url, String soapAction, String body) throws IOException {
        URL u = new URL(url);
        if (!"http".equals(u.getProtocol())) {
            throw new IOException("Only local http is allowed");
        }
        InetAddress addr = InetAddress.getByName(u.getHost());
        if (!isLocal(addr)) {
            throw new IOException("Blocked non-local address " + u.getHost());
        }
        int port = u.getPort() < 0 ? 80 : u.getPort();
        String path = u.getFile().isEmpty() ? "/" : u.getFile();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), 1500);
            s.setSoTimeout(2500);
            StringBuilder req = new StringBuilder();
            req.append(body == null ? "GET " : "POST ").append(path).append(" HTTP/1.0\r\n");
            req.append("Host: ").append(u.getHost()).append(':').append(port).append("\r\n");
            req.append("Connection: close\r\n");
            byte[] payload = null;
            if (body != null) {
                payload = body.getBytes(StandardCharsets.UTF_8);
                req.append("Content-Type: text/xml; charset=\"utf-8\"\r\n");
                req.append("SOAPAction: \"").append(soapAction).append("\"\r\n");
                req.append("Content-Length: ").append(payload.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (payload != null) {
                out.write(payload);
            }
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < MAX_RESPONSE) {
                buf.write(chunk, 0, n);
            }
            String resp = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            int split = resp.indexOf("\r\n\r\n");
            return split >= 0 ? resp.substring(split + 4) : resp;
        }
    }

    /** Text of the first <name>...</name> element, or null. Good enough for UPnP descriptions. */
    static String tag(String xml, String name) {
        if (xml == null) {
            return null;
        }
        int a = xml.indexOf("<" + name + ">");
        if (a < 0) {
            return null;
        }
        a += name.length() + 2;
        int b = xml.indexOf("</" + name + ">", a);
        return b < 0 ? null : xml.substring(a, b).trim();
    }
}
