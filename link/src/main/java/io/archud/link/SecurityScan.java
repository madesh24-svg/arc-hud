package io.archud.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SECURITY CHECK: looks at the devices on the phone's own home Wi-Fi network and reports what is exposed.
 * It only talks to private addresses of the network the phone is on, sends harmless probes (connect, read a
 * banner, ask a question a normal client would ask), never logs in with a password and never changes anything.
 * Findings are exposures and old software, not proven vulnerabilities.
 */
final class SecurityScan {
    static final int INFO = 0;
    static final int REVIEW = 1;
    static final int FIX = 2;

    static final class Finding {
        final int level;
        final String text;
        final String fix;

        Finding(int level, String text, String fix) {
            this.level = level;
            this.text = text;
            this.fix = fix;
        }
    }

    static final class Host {
        final String ip;
        String name = "";
        final List<Integer> open = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();

        Host(String ip) {
            this.ip = ip;
        }

        int worst() {
            int w = INFO;
            for (Finding f : findings) {
                w = Math.max(w, f.level);
            }
            return w;
        }
    }

    interface Listener {
        void progress(String text);

        /** hosts is null when the phone is not on a home network. */
        void done(List<Host> hosts, String base);
    }

    private static final class Ssdp {
        String server = "";
        String location = "";
        boolean gateway;
    }

    private static final class Http {
        int status;
        String server = "";
        String title = "";
        boolean loginForm;
    }

    private static final int[] ALIVE_PORTS = {80, 443, 8080, 53, 22, 445, 8008, 5555};
    private static final int[] MORE_PORTS = {21, 23, 81, 139, 554, 1883, 3389, 5900, 8000, 8009, 8081, 8443, 8888,
            9100};
    private static final int CONNECT_MS = 350;

    private static final Pattern ADMIN = Pattern.compile("(?i)admin|router|gateway|camera|dvr|nvr|setup"
            + "|configuration|management|printer|nas\\b");
    private static final String[][] OLD = {
            {"(?i)apache/(1\\.|2\\.[0-3]\\.)", "Apache web server is end-of-life"},
            {"(?i)openssh[_ ]([1-6]\\.|7\\.[0-3])", "OpenSSH older than 7.4"},
            {"(?i)boa/0\\.9", "Boa web server (abandoned since 2005; common in old cameras and routers)"},
            {"(?i)goahead", "GoAhead embedded web server (old versions have serious known flaws)"},
            {"(?i)microsoft-iis/[1-7]\\.", "Microsoft IIS 7 or older"},
            {"(?i)dropbear[_ ]20(0|10|11|12|13|14|15)", "Dropbear SSH from 2015 or older"},
            {"(?i)lighttpd/1\\.4\\.([0-9]|[1-3][0-9]|4[0-9])\\b", "lighttpd older than 1.4.50"},
            {"(?i)nginx/1\\.(0|1[0-4])\\.", "nginx older than 1.15"},
            {"(?i)php/[1-5]\\.", "PHP 5 or older (end-of-life)"},
            {"(?i)mini_httpd|micro_httpd|thttpd", "Tiny web server that is rarely updated"},
    };
    private static final int[] RISKY_INTERNAL = {21, 22, 23, 80, 139, 445, 3389, 5555, 5900, 8080};

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        // a scanner has to talk to devices whose certificates nobody can verify; nothing is sent but a GET
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private SecurityScan() {
    }

    // ---------------------------------------------------------------- the whole scan

    static void run(final Listener l, final AtomicBoolean cancel) {
        String[] net = localNet();
        if (net == null) {
            l.done(null, null);
            return;
        }
        final String self = net[0];
        final String base = net[1];
        l.progress("LOOKING FOR DEVICES ON " + base + "0 ...");
        final Map<String, Ssdp> ssdp = discover(cancel);
        final Map<String, Host> alive = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        try {
            List<Callable<Void>> phase1 = new ArrayList<>();
            for (int i = 1; i <= 254; i++) {
                final String ip = base + i;
                if (ip.equals(self)) {
                    continue;
                }
                phase1.add(new Callable<Void>() {
                    @Override
                    public Void call() {
                        Host h = null;
                        int silent = 0;
                        for (int p : ALIVE_PORTS) {
                            if (cancel.get()) {
                                return null;
                            }
                            int r = probe(ip, p);
                            if (r == 0) {
                                silent++;
                                if (h == null && silent >= 3) {
                                    break;
                                }
                                continue;
                            }
                            if (h == null) {
                                h = new Host(ip);
                            }
                            if (r == 1) {
                                h.open.add(p);
                            }
                        }
                        if (h == null && ssdp.containsKey(ip)) {
                            h = new Host(ip);
                        }
                        if (h != null) {
                            alive.put(ip, h);
                        }
                        return null;
                    }
                });
            }
            pool.invokeAll(phase1);
            if (cancel.get()) {
                l.done(new ArrayList<Host>(), base);
                return;
            }
            l.progress("FOUND " + alive.size() + " DEVICES. CHECKING EACH ONE ...");
            List<Callable<Void>> phase2 = new ArrayList<>();
            for (final Host h : alive.values()) {
                phase2.add(new Callable<Void>() {
                    @Override
                    public Void call() {
                        if (cancel.get()) {
                            return null;
                        }
                        for (int p : MORE_PORTS) {
                            if (probe(h.ip, p) == 1) {
                                h.open.add(p);
                            }
                        }
                        Collections.sort(h.open);
                        analyze(h, ssdp.get(h.ip));
                        return null;
                    }
                });
            }
            pool.invokeAll(phase2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        List<Host> out = new ArrayList<>(alive.values());
        Collections.sort(out, (a, b) -> Integer.compare(last(a.ip), last(b.ip)));
        l.done(out, base);
    }

    private static int last(String ip) {
        try {
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {the phone's own address, the first three numbers of the network with a trailing dot}, or null. */
    private static String[] localNet() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return new String[] {ip, ip.substring(0, ip.lastIndexOf('.') + 1)};
                    }
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        return null;
    }

    /** 1 = port open, 2 = host answered but the port is closed, 0 = no answer. */
    private static int probe(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), CONNECT_MS);
            return 1;
        } catch (SocketTimeoutException e) {
            return 0;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return m.contains("refused") ? 2 : 0;
        }
    }

    // ---------------------------------------------------------------- UPnP discovery

    private static Map<String, Ssdp> discover(AtomicBoolean cancel) {
        Map<String, Ssdp> out = new HashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(400);
            byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(msg, msg.length, group, 1900));
            long end = System.currentTimeMillis() + 2500;
            byte[] buf = new byte[2048];
            boolean again = true;
            while (System.currentTimeMillis() < end && !cancel.get()) {
                if (again && System.currentTimeMillis() > end - 1800) {
                    s.send(new DatagramPacket(msg, msg.length, group, 1900));
                    again = false;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String ip = p.getAddress().getHostAddress();
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.ISO_8859_1);
                Ssdp d = out.get(ip);
                if (d == null) {
                    d = new Ssdp();
                    out.put(ip, d);
                }
                for (String line : text.split("\r\n")) {
                    String low = line.toLowerCase(Locale.ROOT);
                    if (low.startsWith("server:") && d.server.isEmpty()) {
                        d.server = line.substring(7).trim();
                    } else if (low.startsWith("location:")) {
                        if (d.location.isEmpty() || low.contains("internetgatewaydevice")) {
                            d.location = line.substring(9).trim();
                        }
                    }
                    if ((low.startsWith("st:") || low.startsWith("usn:")) && low.contains("internetgatewaydevice")) {
                        d.gateway = true;
                    }
                }
            }
        } catch (IOException ignored) {
            // the network does not allow multicast: devices are found by their ports only
        }
        return out;
    }

    // ---------------------------------------------------------------- checks on one device

    private static void add(Host h, int level, String text, String fix) {
        synchronized (h) {
            h.findings.add(new Finding(level, text, fix));
        }
    }

    private static void analyze(Host h, Ssdp info) {
        if (info != null && !info.server.isEmpty()) {
            hint(h, info.server);
        }
        if (info != null && !info.location.isEmpty()) {
            describe(h, info);
        }
        for (int port : h.open) {
            switch (port) {
                case 21:
                    ftp(h);
                    break;
                case 22:
                    ssh(h);
                    break;
                case 23:
                    telnet(h);
                    break;
                case 53:
                    add(h, INFO, "A DNS server answers here (normal for a router).", null);
                    break;
                case 80:
                case 81:
                case 8000:
                case 8008:
                case 8080:
                case 8081:
                case 8888:
                    web(h, port, false);
                    break;
                case 443:
                case 8443:
                    tls(h, port);
                    break;
                case 139:
                    if (!h.open.contains(445)) {
                        add(h, REVIEW, "Windows file sharing (NetBIOS) is open.",
                                "Turn file sharing off, or allow only people with a password.");
                    }
                    break;
                case 445:
                    smb(h);
                    break;
                case 554:
                    rtsp(h);
                    break;
                case 1883:
                    mqtt(h);
                    break;
                case 3389:
                    add(h, REVIEW, "Remote Desktop (RDP) is reachable on the network.",
                            "Switch it off unless you use it; keep Windows updated and require a strong password.");
                    break;
                case 5555:
                    add(h, FIX, "Android Debug Bridge (ADB) is open: anyone on this Wi-Fi can ask to control it.",
                            "Switch off wireless debugging or ADB over the network when you are not using it.");
                    break;
                case 5900:
                    add(h, REVIEW, "Remote desktop (VNC) is reachable on the network.",
                            "Use a strong VNC password, or switch it off when not needed.");
                    break;
                case 8009:
                    add(h, INFO, "Google Cast (Chromecast or a TV with Cast) is on.", null);
                    break;
                case 9100:
                    add(h, INFO, "Printer port is open; printers often have no password.", null);
                    break;
                default:
                    break;
            }
        }
        if (info != null && info.gateway) {
            upnp(h, info);
        }
    }

    /** Old or abandoned software named in a banner or Server line. These are hints, not proven vulnerabilities. */
    private static void hint(Host h, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String[] rule : OLD) {
            if (Pattern.compile(rule[0]).matcher(text).find()) {
                add(h, REVIEW, "Old software: " + rule[1] + ".",
                        "Check this device for a firmware or software update.");
                return;
            }
        }
    }

    private static void telnet(Host h) {
        String banner = "";
        try (Socket s = plain(h.ip, 23)) {
            s.setSoTimeout(1200);
            banner = printable(read(s.getInputStream(), 200));
        } catch (IOException ignored) {
            // no banner
        }
        add(h, FIX, "Telnet is open: remote login with no encryption." + (banner.isEmpty() ? "" : " Says: " + banner),
                "Turn telnet off on this device; use SSH if you need remote access.");
    }

    private static void ssh(Host h) {
        try (Socket s = plain(h.ip, 22)) {
            String line = readLine(s.getInputStream());
            if (!line.isEmpty()) {
                add(h, INFO, "SSH: " + line, null);
                hint(h, line);
            }
        } catch (IOException ignored) {
            // closed again
        }
    }

    private static void ftp(Host h) {
        try (Socket s = plain(h.ip, 21)) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String banner = readLine(in);
            out.write("USER anonymous\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readLine(in);
            out.write("PASS arc@check.local\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String answer = readLine(in);
            if (answer.startsWith("230")) {
                add(h, FIX, "FTP lets anyone in without a password (anonymous login).",
                        "Turn anonymous FTP off, or switch FTP off.");
            } else {
                add(h, REVIEW, "FTP is open: passwords and files travel without encryption.",
                        "Use SFTP instead, or switch FTP off.");
            }
            hint(h, banner);
        } catch (IOException ignored) {
            // closed again
        }
    }

    private static void smb(Host h) {
        boolean smb1 = false;
        try (Socket s = plain(h.ip, 445)) {
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(hex("0000002f" + "ff534d42" + "72" + "00000000" + "18" + "0128" + "0000" + "0000000000000000"
                    + "0000" + "0000" + "2f4b" + "0000" + "c55e" + "00" + "0c00" + "02" + "4e54204c4d20302e3132"
                    + "00"));
            out.flush();
            byte[] r = read(s.getInputStream(), 64);
            smb1 = r.length >= 13 && (r[4] & 0xFF) == 0xFF && r[5] == 'S' && r[6] == 'M' && r[7] == 'B'
                    && r[9] == 0 && r[10] == 0 && r[11] == 0 && r[12] == 0;
        } catch (IOException ignored) {
            // no answer
        }
        add(h, REVIEW, "Windows file sharing (SMB) is open.",
                "Turn file sharing off, or allow only people with a password.");
        if (smb1) {
            add(h, FIX, "SMBv1 is switched on (the old protocol behind WannaCry).",
                    "Turn SMB1 off: Windows Features, untick SMB 1.0/CIFS; update the device.");
        }
    }

    private static void rtsp(Host h) {
        try (Socket s = plain(h.ip, 554)) {
            s.setSoTimeout(2000);
            s.getOutputStream().write(("DESCRIBE rtsp://" + h.ip + ":554/ RTSP/1.0\r\nCSeq: 1\r\n"
                    + "Accept: application/sdp\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String text = new String(read(s.getInputStream(), 300), StandardCharsets.ISO_8859_1);
            if (text.startsWith("RTSP/1.0 200")) {
                add(h, FIX, "A camera video stream opens with no password.",
                        "Set a password on the camera's video stream.");
            } else if (text.contains("401")) {
                add(h, INFO, "A video stream is here and it asks for a password.", null);
            } else {
                add(h, INFO, "A video (RTSP) port is open.", null);
            }
        } catch (IOException ignored) {
            // no answer
        }
    }

    private static void mqtt(Host h) {
        try (Socket s = plain(h.ip, 1883)) {
            s.setSoTimeout(2000);
            s.getOutputStream().write(hex("100f" + "0004" + "4d515454" + "04" + "02" + "003c" + "0003" + "617263"));
            s.getOutputStream().flush();
            byte[] r = read(s.getInputStream(), 8);
            if (r.length >= 4 && r[0] == 0x20 && r[3] == 0) {
                add(h, FIX, "The MQTT broker accepts anyone without a password.",
                        "Require a username and password on the MQTT broker.");
            } else {
                add(h, INFO, "An MQTT broker is here and it asks for a login.", null);
            }
        } catch (IOException ignored) {
            // no answer
        }
    }

    // ---------------------------------------------------------------- web pages and HTTPS

    private static void web(Host h, int port, boolean tls) {
        Http r = http(h.ip, port, tls, "/");
        if (r == null) {
            return;
        }
        if (!r.title.isEmpty() && h.name.isEmpty() && r.title.length() <= 30) {
            h.name = r.title;
        }
        String where = (tls ? "HTTPS" : "HTTP") + " port " + port;
        if (r.status == 401) {
            add(h, INFO, where + " asks for a login.", null);
        } else if (r.status == 200 && !r.loginForm && ADMIN.matcher(r.title + " " + r.server).find()) {
            add(h, REVIEW, where + " shows an admin-style page without asking for a login"
                    + (r.title.isEmpty() ? "." : " (\"" + r.title + "\")."), "Set an admin password on this device.");
        } else if (r.status > 0) {
            add(h, INFO, where + " serves a web page" + (r.server.isEmpty() ? "." : " (" + r.server + ")."), null);
        }
        if (!tls && r.loginForm) {
            add(h, REVIEW, where + " has a login page that sends the password without encryption.",
                    "Use HTTPS if the device offers it; log in only from trusted Wi-Fi.");
        }
        hint(h, r.server);
    }

    private static void tls(Host h, int port) {
        try (SSLSocket s = tlsSocket(h.ip, port, null)) {
            Certificate[] certs = s.getSession().getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate) {
                X509Certificate c = (X509Certificate) certs[0];
                if (c.getNotAfter().getTime() < System.currentTimeMillis()) {
                    add(h, REVIEW, "The HTTPS certificate on port " + port + " has expired.",
                            "Renew or replace the certificate on this device.");
                }
            }
        } catch (Exception ignored) {
            // not a normal HTTPS server, or it refuses us
        }
        String[] oldOnes = {"TLSv1", "TLSv1.1"};
        for (String old : oldOnes) {
            try (SSLSocket s = tlsSocket(h.ip, port, new String[] {old})) {
                add(h, FIX, "HTTPS on port " + port + " still accepts the old " + old.replace("v", " ") + ".",
                        "Update the device's firmware, or turn off old TLS versions in its settings.");
                break;
            } catch (Exception ignored) {
                // that version is refused, which is what we want
            }
        }
        web(h, port, true);
    }

    private static Http http(String ip, int port, boolean tls, String path) {
        Socket s = null;
        try {
            s = tls ? tlsSocket(ip, port, null) : plain(ip, port);
            s.setSoTimeout(1800);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + ip + "\r\nUser-Agent: arc-check\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String text = new String(read(s.getInputStream(), 6000), StandardCharsets.ISO_8859_1);
            if (!text.startsWith("HTTP/")) {
                return null;
            }
            Http r = new Http();
            String[] parts = text.substring(0, text.indexOf('\r') > 0 ? text.indexOf('\r') : text.length())
                    .split(" ");
            if (parts.length >= 2) {
                try {
                    r.status = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    r.status = 0;
                }
            }
            int split = text.indexOf("\r\n\r\n");
            String head = split > 0 ? text.substring(0, split) : text;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("server:")) {
                    r.server = line.substring(7).trim();
                }
            }
            String body = split > 0 ? text.substring(split + 4) : "";
            String low = body.toLowerCase(Locale.ROOT);
            int a = low.indexOf("<title");
            if (a >= 0) {
                int b = low.indexOf('>', a);
                int c = low.indexOf("</title>", b + 1);
                if (b > 0 && c > b) {
                    r.title = body.substring(b + 1, c).replaceAll("\\s+", " ").trim();
                }
            }
            r.loginForm = low.contains("type=\"password\"") || low.contains("type='password'")
                    || low.contains("type=password");
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(s);
        }
    }

    private static SSLSocket tlsSocket(String ip, int port, String[] protocols) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {TRUST_ALL}, new SecureRandom());
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(ip, port), 800);
            raw.setSoTimeout(2000);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, ip, port, true);
            if (protocols != null) {
                s.setEnabledProtocols(protocols);
            }
            s.startHandshake();
            return s;
        } catch (Exception e) {
            closeQuietly(raw);
            throw e;
        }
    }

    // ---------------------------------------------------------------- UPnP description and port mappings

    /** Reads the device description a UPnP device offers on the network, for a friendlier name. */
    private static void describe(Host h, Ssdp info) {
        String xml = fetch(h.ip, info.location);
        if (xml.isEmpty()) {
            return;
        }
        String name = tag(xml, "friendlyName");
        if (name.isEmpty()) {
            name = (tag(xml, "manufacturer") + " " + tag(xml, "modelName")).trim();
        }
        if (!name.isEmpty() && (h.name.isEmpty() || info.gateway)) {
            h.name = name.length() > 40 ? name.substring(0, 40) : name;
        }
    }

    /** GET the address a device announced, but only if it points back at that same device. */
    private static String fetch(String ip, String location) {
        try {
            URL u = new URL(location);
            if (!ip.equals(u.getHost())) {
                return "";
            }
            return httpBody(ip, u.getPort() == -1 ? 80 : u.getPort(), "GET", u.getFile(), null, null);
        } catch (IOException e) {
            return "";
        }
    }

    private static String httpBody(String ip, int port, String method, String path, String[] headers, String body) {
        try (Socket s = plain(ip, port)) {
            s.setSoTimeout(2000);
            StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.0\r\nHost: ")
                    .append(ip).append(':').append(port).append("\r\nConnection: close\r\n");
            if (headers != null) {
                for (String x : headers) {
                    sb.append(x).append("\r\n");
                }
            }
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (body != null) {
                sb.append("Content-Length: ").append(payload.length).append("\r\n");
            }
            sb.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            String text = new String(read(s.getInputStream(), 20000), StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            if (split < 0 || !text.startsWith("HTTP/1.") || !text.substring(0, Math.min(text.length(), 16))
                    .contains(" 200")) {
                return "";
            }
            return text.substring(split + 4);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">");
        if (a < 0) {
            return "";
        }
        int b = xml.indexOf("</" + name + ">", a);
        return b < 0 ? "" : xml.substring(a + name.length() + 2, b).trim();
    }

    /** Lists the ports the router has opened to the internet for devices on this network (read-only). */
    private static void upnp(Host h, Ssdp info) {
        String xml = fetch(h.ip, info.location);
        if (xml.isEmpty()) {
            return;
        }
        String type = "";
        String control = "";
        int from = 0;
        while (true) {
            int a = xml.indexOf("<service>", from);
            if (a < 0) {
                break;
            }
            int b = xml.indexOf("</service>", a);
            if (b < 0) {
                break;
            }
            String block = xml.substring(a, b);
            String t = tag(block, "serviceType");
            if (t.contains("WANIPConnection") || t.contains("WANPPPConnection")) {
                type = t;
                control = tag(block, "controlURL");
                break;
            }
            from = b;
        }
        if (type.isEmpty() || control.isEmpty()) {
            return;
        }
        int port = 80;
        try {
            URL u = new URL(info.location);
            port = u.getPort() == -1 ? 80 : u.getPort();
            if (control.startsWith("http")) {
                control = new URL(control).getFile();
            }
        } catch (IOException e) {
            return;
        }
        if (!control.startsWith("/")) {
            control = "/" + control;
        }
        add(h, REVIEW, "UPnP is on: devices on your network can open ports to the internet by themselves.",
                "If nothing you use needs it, switch UPnP off in the router's settings.");
        for (int i = 0; i < 20; i++) {
            String soap = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                    + "<u:GetGenericPortMappingEntry xmlns:u=\"" + type + "\"><NewPortMappingIndex>" + i
                    + "</NewPortMappingIndex></u:GetGenericPortMappingEntry></s:Body></s:Envelope>";
            String r = httpBody(h.ip, port, "POST", control, new String[] {
                    "Content-Type: text/xml; charset=\"utf-8\"",
                    "SOAPAction: \"" + type + "#GetGenericPortMappingEntry\""}, soap);
            if (r.isEmpty()) {
                break;
            }
            String ext = tag(r, "NewExternalPort");
            String client = tag(r, "NewInternalClient");
            String inner = tag(r, "NewInternalPort");
            String proto = tag(r, "NewProtocol").toLowerCase(Locale.ROOT);
            String desc = tag(r, "NewPortMappingDescription");
            if (ext.isEmpty()) {
                break;
            }
            boolean risky = false;
            for (int p : RISKY_INTERNAL) {
                if (String.valueOf(p).equals(inner)) {
                    risky = true;
                }
            }
            add(h, risky ? FIX : REVIEW, "Internet port " + ext + "/" + proto + " is forwarded to " + client + ":"
                    + inner + (desc.isEmpty() ? "" : " (" + desc + ")") + ".",
                    "Remove this forward in the router, and find which app created it.");
        }
    }

    // ---------------------------------------------------------------- small helpers

    private static Socket plain(String ip, int port) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 800);
            s.setSoTimeout(1500);
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** Reads until the other side closes, the limit is reached or nothing arrives for the socket's timeout. */
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        try {
            while (buf.size() < max) {
                int n = in.read(chunk, 0, Math.min(chunk.length, max - buf.size()));
                if (n < 0) {
                    break;
                }
                buf.write(chunk, 0, n);
            }
        } catch (SocketTimeoutException ignored) {
            // what arrived so far is all there is
        }
        return buf.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try {
            while (sb.length() < 300) {
                int c = in.read();
                if (c < 0 || c == '\n') {
                    break;
                }
                if (c != '\r') {
                    sb.append((char) c);
                }
            }
        } catch (SocketTimeoutException ignored) {
            // take what we have
        }
        return printable(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String printable(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (b >= 32 && b < 127) {
                sb.append((char) b);
            }
        }
        String s = sb.toString().trim();
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
