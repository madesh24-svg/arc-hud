package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * ARC's own remote control of THIS TV, through the remote service Google TV and Android TV ship (the one their phone
 * remote uses). ARC pairs with it once, over the TV's own loopback address, and from then on is the only remote that
 * service knows: every phone talks to ARC with its PIN and never pairs with the TV itself. Key presses reach every
 * app, YouTube included. Verified on a real Android TV box: keys, volume, typing, app links.
 */
final class AtvRemote {
    static final String PACKAGE = "com.google.android.tv.remote.service";
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String HOST = "127.0.0.1";
    private static final int PAIR_PORT = 6467;
    private static final int REMOTE_PORT = 6466;
    private static final String PREFS = "atv";
    private static final String FP = "fp";
    private static final long CONFIGURE_FLAGS = 622;
    private static final String FORGOTTEN = "ARC is no longer paired with this TV's remote service. Pair again.";

    private static Session current;
    private static Pairing pairing;
    private static volatile String foreground;
    private static volatile int volumeLevel = -1;
    private static volatile int volumeMax = -1;
    private static volatile boolean volumeMuted;
    private static volatile String lastError = "";

    private AtvRemote() {
    }

    /** The last reason the link to the remote service failed, for the phone's status line and the TV's log. */
    static String lastError() {
        return lastError;
    }

    private static void note(String where, Exception e) {
        String m = where + ": " + e;
        lastError = m.replace('\n', ' ');
        android.util.Log.w("ArcAtv", m, e);
    }

    // ------------------------------------------------------------------ state

    /** True when this TV has Google's remote service, so this module can work at all. */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean paired(Context c) {
        return prefs(c).getString(FP, null) != null;
    }

    static synchronized void forget(Context c) {
        prefs(c).edit().remove(FP).apply();
        close();
    }

    static String foreground() {
        return foreground;
    }

    static int volumeLevel() {
        return volumeLevel;
    }

    static int volumeMax() {
        return volumeMax;
    }

    static boolean volumeMuted() {
        return volumeMuted;
    }

    /** Android key code for a REMOTE control name, or 0 when this service has no such key. */
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

    // ------------------------------------------------------------------ pairing (once per TV)

    /** Starts pairing; the TV then shows a 6-character code on its screen. */
    static synchronized void startPairing(Context c) throws IOException {
        if (!available(c)) {
            throw new IOException("This TV has no Google remote service.");
        }
        if (pairing != null) {
            pairing.close();
            pairing = null;
        }
        pairing = new Pairing(c.getApplicationContext());
    }

    /** Finishes pairing with the code the TV shows. */
    static synchronized void finishPairing(Context c, String code) throws IOException {
        Pairing p = pairing;
        if (p == null) {
            throw new IOException("Start pairing first, then send the code the TV shows.");
        }
        pairing = null;
        p.finish(code);
        close();
    }

    // ------------------------------------------------------------------ using it

    private static synchronized Session session(Context c) throws IOException {
        if (current != null && !current.dead) {
            return current;
        }
        close();
        try {
            current = new Session(c.getApplicationContext());
            lastError = "";
            android.util.Log.i("ArcAtv", "link to the remote service is up");
        } catch (IOException e) {
            note("connect", e);
            String m = String.valueOf(e.getMessage());
            if (m.contains("CERTIFICATE_UNKNOWN")) {
                forget(c);
                throw new IOException(FORGOTTEN);
            }
            throw e;
        }
        return current;
    }

    /** Opens (or reuses) the link so the TV's volume and front-app reports keep arriving. */
    static void connect(Context c) throws IOException {
        session(c);
    }

    static synchronized void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    /** One key press; reconnects once if the link had dropped. */
    static void key(Context c, int code) throws IOException {
        try {
            session(c).key(code);
        } catch (IOException first) {
            note("key", first);
            close();
            session(c).key(code);
        }
    }

    /** Types into the focused text box. True when the TV echoed the new text; some apps never do. */
    static boolean text(Context c, String value) throws IOException {
        try {
            return session(c).text(value);
        } catch (IOException first) {
            close();
            return session(c).text(value);
        }
    }

    /** Opens a link (web address or app link) on the TV. */
    static void link(Context c, String url) throws IOException {
        try {
            session(c).link(url);
        } catch (IOException first) {
            close();
            session(c).link(url);
        }
    }

    /** Opens YouTube's results for a search; spaces must be %20 because YouTube reads a "+" literally. */
    static void youtubeSearch(Context c, String query) throws IOException {
        String q = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        link(c, "https://www.youtube.com/results?search_query=" + q);
    }

    /**
     * Types text the smart way. With YouTube in front the text becomes a search link, which replaces the old query,
     * submits it and works in every language (YouTube's own box only appends typed text). Elsewhere it is typed
     * into the focused text box, which replaces that box's text.
     */
    static String typeSmart(Context c, String text) throws IOException {
        if (YOUTUBE.equals(foreground)) {
            youtubeSearch(c, text.trim());
            return "Searching YouTube on the TV.";
        }
        return text(c, text) ? "Typed on the TV."
                : "Sent to the TV. Some apps do not confirm, so check the screen.";
    }

    // ------------------------------------------------------------------ TLS and framing

    private static SSLSocket tls(Context c, int port, final String expectedFp, final String[] seenFp)
            throws IOException {
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
                        throw new CertificateException("The TV's remote service certificate changed. Pair again.");
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
            raw.connect(new InetSocketAddress(HOST, port), 4000);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, HOST, port, true);
            s.setSoTimeout(15000);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            throw new IOException("Secure connection failed: " + e.getMessage());
        }
    }

    /** Always offers our certificate; Android's default manager hides it when the service lists no matching issuer. */
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
                sb.append(String.format(Locale.ROOT, "%02x", b));
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
                throw new IOException("Bad reply from the remote service");
            }
        }
        if (n < 0 || n > 65536) {
            throw new IOException("Bad reply from the remote service");
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

    // ------------------------------------------------------------------ the pairing conversation

    private static final class Pairing {
        private final Context app;
        private SSLSocket socket;
        private InputStream in;
        private OutputStream out;
        private String serverFp;
        private byte[] serverMod;
        private byte[] serverExp;
        private byte[] clientMod;
        private byte[] clientExp;

        Pairing(Context c) throws IOException {
            this.app = c;
            String[] seen = new String[1];
            socket = tls(app, PAIR_PORT, null, seen);
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

                send(outer().msg(10, new Pb.W().str(1, "atvremote").str(2, "ARC")));
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
                throw new IOException("The remote service refused pairing (status " + status + ").");
            }
            if (!m.containsKey(field)) {
                throw new IOException("Unexpected reply from the remote service while pairing.");
            }
        }

        void finish(String codeText) throws IOException {
            String code = codeText == null ? "" : codeText.trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[0-9A-F]{6}")) {
                close();
                throw new IOException("The code has 6 characters: digits and the letters A to F.");
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
                prefs(app).edit().putString(FP, serverFp).apply();
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
        private volatile String failure;
        /** Counts the text-field updates the TV has sent, so a typed edit can be confirmed. */
        private volatile int fieldUpdates;

        Session(Context c) throws IOException {
            String fp = prefs(c).getString(FP, null);
            if (fp == null) {
                throw new IOException("ARC is not paired with this TV's remote service yet.");
            }
            socket = tls(c, REMOTE_PORT, fp, null);
            socket.setSoTimeout(0);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            Thread reader = new Thread(this::readLoop, "atv-remote");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!ready.await(6, TimeUnit.SECONDS)) {
                    String f = failure;
                    close();
                    throw new IOException(f != null ? f : "The remote service did not answer.");
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
                failure = e.getMessage();
                note("link", e);
            } finally {
                close();
            }
        }

        private void handle(Map<Integer, List<Object>> m) throws IOException {
            if (m.containsKey(1)) {
                Pb.W info = new Pb.W().str(1, "ARC").str(2, "ARC").num(3, 1).str(4, "1")
                        .str(5, "io.archud").str(6, "1.0");
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
                if (raw != null) {
                    Map<Integer, List<Object>> v = Pb.parse(raw);
                    volumeLevel = (int) Pb.num(v, 7, 0);
                    volumeMax = (int) Pb.num(v, 6, 0);
                    volumeMuted = Pb.num(v, 8, 0) != 0;
                }
            } else if (m.containsKey(20)) {
                byte[] raw = Pb.bytes(m, 20);
                byte[] info = raw == null ? null : Pb.bytes(Pb.parse(raw), 1);
                String pkg = info == null ? null : Pb.str(Pb.parse(info), 12);
                if (pkg != null) {
                    foreground = pkg;
                }
            } else if (m.containsKey(22)) {
                fieldUpdates++;
            } else if (m.containsKey(3)) {
                close();
            }
        }

        void key(int code) throws IOException {
            check();
            sendFrame(out, new Pb.W().msg(10, new Pb.W().num(1, code).num(2, 3)).toBytes());
        }

        void link(String url) throws IOException {
            check();
            sendFrame(out, new Pb.W().msg(90, new Pb.W().str(1, url)).toBytes());
        }

        /** Sets the text of the focused text box; waits briefly for the TV's echo. */
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

        private void check() throws IOException {
            if (dead) {
                throw new IOException("The link to the remote service dropped.");
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
