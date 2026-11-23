package io.archud.link;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The remote protocol Google TV and Android TV use for their own phone remote (TLS on ports 6467 for the one-time
 * pairing and 6466 for the keys). It sends real key presses to whatever app is in front, YouTube included. Every
 * address must be on the home network, and after pairing the TV's certificate is pinned.
 */
final class AtvRemote {
    static final int PAIR_PORT = 6467;
    static final int REMOTE_PORT = 6466;
    private static final String PREFS = "atv";
    private static final long CONFIGURE_FLAGS = 622;

    /** Called from a background thread with what the TV reports. */
    interface Listener {
        void volume(int level, int max, boolean muted);

        void app(String packageName);
    }

    private static volatile Listener listener;
    private static Session current;
    private static String currentHost;

    static final String YOUTUBE = "com.google.android.youtube.tv";
    /** The app in front on the TV, as the TV reports it while the link is up; null when unknown. */
    private static volatile String foreground;

    static String foreground() {
        return foreground;
    }

    private AtvRemote() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ paired state

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean paired(Context c, String host) {
        return host != null && prefs(c).getString("fp_" + host, null) != null;
    }

    static synchronized void forget(Context c, String host) {
        prefs(c).edit().remove("fp_" + host).apply();
        close();
    }

    /** Android key code for a REMOTE screen control, or 0 when this protocol has no such key. */
    static int keycode(String k) {
        switch (k) {
            case "home":
                return 3;
            case "back":
                return 4;
            case "recents":
                return 187;
            case "navup":
                return 19;
            case "navdown":
                return 20;
            case "navleft":
                return 21;
            case "navright":
                return 22;
            case "ok":
                return 23;
            case "scrollup":
                return 92;
            case "scrolldown":
                return 93;
            case "volup":
                return 24;
            case "voldown":
                return 25;
            case "mute":
                return 164;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ sessions

    private static synchronized Session session(Context c, String host) throws IOException {
        if (current != null && host.equals(currentHost) && !current.dead) {
            return current;
        }
        close();
        current = new Session(c.getApplicationContext(), host);
        currentHost = host;
        return current;
    }

    /** Opens (or reuses) the link to the TV so its volume and app reports start arriving. */
    static void connect(Context c, String host) throws IOException {
        session(c, host);
    }

    static synchronized void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    /** One key press; reconnects once if the link had dropped. */
    static void key(Context c, String host, int code) throws IOException {
        try {
            session(c, host).key(code);
        } catch (IOException first) {
            close();
            session(c, host).key(code);
        }
    }

    /**
     * Types text into the text box focused on the TV (verified in BrowseHere and in YouTube's own search box). True
     * when the TV echoed the new text back; some apps, YouTube among them, accept the text without echoing it.
     */
    static boolean text(Context c, String host, String value) throws IOException {
        try {
            return session(c, host).text(value);
        } catch (IOException first) {
            close();
            return session(c, host).text(value);
        }
    }

    /** Opens a link (a web address or app link) on the TV. */
    static void link(Context c, String host, String url) throws IOException {
        try {
            session(c, host).link(url);
        } catch (IOException first) {
            close();
            session(c, host).link(url);
        }
    }

    // ------------------------------------------------------------------ TLS and framing

    private static SSLSocket tls(Context c, String host, int port, final String expectedFp, final String[] seenFp)
            throws IOException {
        if (!TvClient.isLocalIp(host)) {
            throw new IOException("The TV address must be a home-network address like 192.168.0.20");
        }
        try {
            KeyStore ks = AtvCert.keyStore(c);
            PrivateKey key = (PrivateKey) ks.getKey(AtvCert.ALIAS, AtvCert.PASS);
            X509Certificate[] chain = {(X509Certificate) ks.getCertificate(AtvCert.ALIAS)};
            TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String type) throws CertificateException {
                    throw new CertificateException("not a server");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String type) throws CertificateException {
                    String fp = fingerprint(certs[0]);
                    if (seenFp != null) {
                        seenFp[0] = fp;
                    }
                    if (expectedFp != null && !expectedFp.equals(fp)) {
                        throw new CertificateException("The TV's certificate changed. Pair again.");
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new javax.net.ssl.KeyManager[] {new FixedKey(key, chain)}, new TrustManager[] {tm}, null);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 4000);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(15000);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException("Secure connection failed: " + e.getMessage());
        }
    }

    /** Always offers our certificate; Android's default manager hides it when the TV lists no matching issuer. */
    private static final class FixedKey implements X509KeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;

        FixedKey(PrivateKey key, X509Certificate[] chain) {
            this.key = key;
            this.chain = chain;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {AtvCert.ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return AtvCert.ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }
    }

    private static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    private static void sendFrame(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = payload.length;
        while (n > 0x7F) {
            b.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        b.write(n);
        b.write(payload, 0, payload.length);
        synchronized (out) {
            out.write(b.toByteArray());
            out.flush();
        }
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int n = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            n |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("Bad reply from the TV");
            }
        }
        if (n < 0 || n > 65536) {
            throw new IOException("Bad reply from the TV");
        }
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(data, off, n - off);
            if (r < 0) {
                throw new EOFException();
            }
            off += r;
        }
        return data;
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    // ------------------------------------------------------------------ pairing (once per TV)

    /** Steps 1 to 3 of pairing; the TV then shows a 6-character code. Call finish with that code. */
    static final class Pairing {
        private final Context app;
        private final String host;
        private SSLSocket socket;
        private InputStream in;
        private OutputStream out;
        private String serverFp;
        private byte[] serverMod;
        private byte[] serverExp;
        private byte[] clientMod;
        private byte[] clientExp;

        Pairing(Context c, String host) throws IOException {
            this.app = c.getApplicationContext();
            this.host = host;
            String[] seen = new String[1];
            socket = tls(app, host, PAIR_PORT, null, seen);
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                serverFp = seen[0];
                RSAPublicKey sk = (RSAPublicKey) socket.getSession().getPeerCertificates()[0].getPublicKey();
                serverMod = unsigned(sk.getModulus());
                serverExp = unsigned(sk.getPublicExponent());
                RSAPublicKey ck = (RSAPublicKey) AtvCert.keyStore(app).getCertificate(AtvCert.ALIAS).getPublicKey();
                clientMod = unsigned(ck.getModulus());
                clientExp = unsigned(ck.getPublicExponent());

                send(outer().msg(10, new Pb.W().str(1, "atvremote").str(2, "ARC LINK")));
                expect(11);
                send(outer().msg(20, new Pb.W().msg(1, encoding()).num(3, 1)));
                expect(20);
                send(outer().msg(30, new Pb.W().msg(1, encoding()).num(2, 1)));
                expect(31);
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                close();
                throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
        }

        private Pb.W outer() {
            return new Pb.W().num(1, 2).num(2, 200);
        }

        private Pb.W encoding() {
            return new Pb.W().num(1, 3).num(2, 6);
        }

        private void send(Pb.W message) throws IOException {
            sendFrame(out, message.toBytes());
        }

        private void expect(int field) throws IOException {
            Map<Integer, List<Object>> m = Pb.parse(readFrame(in));
            long status = Pb.num(m, 2, 200);
            if (status != 200) {
                throw new IOException("The TV refused pairing (status " + status + ").");
            }
            if (!m.containsKey(field)) {
                throw new IOException("Unexpected reply from the TV while pairing.");
            }
        }

        /** Step 4: proves we saw the code. Stores the TV's certificate on success. */
        void finish(String codeText) throws IOException {
            String code = codeText == null ? "" : codeText.trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("[0-9A-F]{6}")) {
                throw new IOException("The code has 6 characters, digits and the letters A to F.");
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(clientMod);
                md.update(clientExp);
                md.update(serverMod);
                md.update(serverExp);
                md.update(new byte[] {(byte) Integer.parseInt(code.substring(2, 4), 16),
                        (byte) Integer.parseInt(code.substring(4, 6), 16)});
                byte[] digest = md.digest();
                if ((digest[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
                    throw new IOException("That code does not match. Check the TV screen and try again.");
                }
                send(outer().msg(40, new Pb.W().bytes(1, digest)));
                expect(41);
                prefs(app).edit().putString("fp_" + host, serverFp).apply();
            } catch (GeneralSecurityException e) {
                throw new IOException(e.getMessage());
            } finally {
                close();
            }
        }

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    // ------------------------------------------------------------------ the running link

    private static final class Session {
        private final SSLSocket socket;
        private final InputStream in;
        private final OutputStream out;
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile boolean dead;
        /** Counts the text-field updates the TV has sent, so a typed edit can be confirmed. */
        private volatile int fieldUpdates;

        Session(Context c, String host) throws IOException {
            String fp = prefs(c).getString("fp_" + host, null);
            if (fp == null) {
                throw new IOException("Not paired with this TV's remote yet.");
            }
            socket = tls(c, host, REMOTE_PORT, fp, null);
            socket.setSoTimeout(0);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Thread reader = new Thread(this::readLoop, "atv-remote");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!ready.await(6, TimeUnit.SECONDS)) {
                    close();
                    throw new IOException("The TV did not answer. Is it on?");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new IOException("Interrupted");
            }
        }

        private void readLoop() {
            try {
                while (!dead) {
                    byte[] frame = readFrame(in);
                    try {
                        handle(Pb.parse(frame));
                    } catch (RuntimeException ignored) {
                        // one odd message must not end the link
                    }
                }
            } catch (IOException e) {
                // the TV closed the link or the network dropped
            } finally {
                close();
            }
        }

        private void handle(Map<Integer, List<Object>> m) throws IOException {
            if (m.containsKey(1)) {
                Pb.W info = new Pb.W().str(1, "ARC LINK").str(2, "ARC").num(3, 1).str(4, "1")
                        .str(5, "io.archud.link").str(6, "1.0");
                sendFrame(out, new Pb.W().msg(1, new Pb.W().num(1, CONFIGURE_FLAGS).msg(2, info)).toBytes());
            } else if (m.containsKey(2)) {
                sendFrame(out, new Pb.W().msg(2, new Pb.W().num(1, CONFIGURE_FLAGS)).toBytes());
            } else if (m.containsKey(8)) {
                byte[] req = Pb.bytes(m, 8);
                long val = req == null ? 0 : Pb.num(Pb.parse(req), 1, 0);
                sendFrame(out, new Pb.W().msg(9, new Pb.W().num(1, val)).toBytes());
            } else if (m.containsKey(40)) {
                ready.countDown();
            } else if (m.containsKey(50)) {
                byte[] raw = Pb.bytes(m, 50);
                Listener l = listener;
                if (raw != null && l != null) {
                    Map<Integer, List<Object>> v = Pb.parse(raw);
                    l.volume((int) Pb.num(v, 7, 0), (int) Pb.num(v, 6, 0), Pb.num(v, 8, 0) != 0);
                }
            } else if (m.containsKey(20)) {
                byte[] raw = Pb.bytes(m, 20);
                Listener l = listener;
                byte[] info = raw == null ? null : Pb.bytes(Pb.parse(raw), 1);
                String pkg = info == null ? null : Pb.str(Pb.parse(info), 12);
                if (pkg != null) {
                    foreground = pkg;
                    if (l != null) {
                        l.app(pkg);
                    }
                }
            } else if (m.containsKey(22)) {
                fieldUpdates++;
            } else if (m.containsKey(3)) {
                close();
            }
        }

        /** Inserts text at the cursor of the selected text box; waits briefly for the TV's echo. */
        boolean text(String value) throws IOException {
            check();
            int before = fieldUpdates;
            int pos = Math.max(0, value.length() - 1);
            Pb.W edit = new Pb.W().num(1, 1).msg(2, new Pb.W().num(1, pos).num(2, pos).str(3, value));
            sendFrame(out, new Pb.W().msg(21, new Pb.W().num(1, 0).num(2, 0).msg(3, edit)).toBytes());
            for (int i = 0; i < 15; i++) {
                if (fieldUpdates != before) {
                    return true;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return fieldUpdates != before;
        }

        void key(int code) throws IOException {
            check();
            sendFrame(out, new Pb.W().msg(10, new Pb.W().num(1, code).num(2, 3)).toBytes());
        }

        void link(String url) throws IOException {
            check();
            sendFrame(out, new Pb.W().msg(90, new Pb.W().str(1, url)).toBytes());
        }

        private void check() throws IOException {
            if (dead) {
                throw new IOException("The link to the TV dropped.");
            }
        }

        void close() {
            dead = true;
            foreground = null;
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
