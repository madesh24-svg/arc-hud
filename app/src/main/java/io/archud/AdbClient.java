package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Real key presses for every app on THIS TV, through the box's own network adb (the "USB debugging" service that
 * many TV boxes leave listening on port 5555). The user taps ALLOW once on the TV; after that ARC is a trusted adb
 * client and presses keys the way a physical remote would, YouTube included.
 *
 * Safety rules, on purpose: it connects only to the TV's own loopback address; the only thing it ever runs is
 * "input keyevent N" for a fixed list of key codes (no command text from a phone ever reaches a shell); and the adb
 * key is made on this TV, kept in ARC's private storage (backup is off) and never sent anywhere.
 */
final class AdbClient {
    static final int OFF = 0;
    static final int WAITING = 1;
    static final int ON = 2;

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final String PREFS = "adb";
    private static final String AUTHORIZED = "authorized";
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};
    /** The only key codes ARC will ever send: Home, Back, arrows, OK, menu, play/pause, scroll, volume, recents, sleep. */
    private static final Set<Integer> KEYS = new HashSet<>(Arrays.asList(
            3, 4, 19, 20, 21, 22, 23, 24, 25, 82, 85, 92, 93, 164, 187, 223, 224));
    private static final String UNAUTHORIZED = "ARC is not allowed to use adb on this TV. Enable real keys again.";

    private static volatile int state = OFF;
    private static volatile String lastError = "";
    private static Conn conn;
    private static PrivateKey privateKey;
    private static RSAPublicKey publicKey;

    private AdbClient() {
    }

    // ------------------------------------------------------------------ state

    static int state() {
        return state;
    }

    static String lastError() {
        return lastError;
    }

    /** True once the user has tapped ALLOW on the TV for ARC's key. */
    static boolean authorized(Context c) {
        return prefs(c).getBoolean(AUTHORIZED, false);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when this TV is listening for adb on its own loopback address. */
    static boolean available() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Android key code for a REMOTE control name, or 0 when adb has no such key. */
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
            case "screenoff":
                return 223;
            case "screenon":
                return 224;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ enabling (one tap on the TV)

    /** Starts the one-time authorization: the TV shows an ALLOW dialog; the user ticks "always" and taps ALLOW. */
    static void startEnabling(final Context c) {
        if (state == WAITING) {
            return;
        }
        state = WAITING;
        lastError = "";
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Conn fresh = new Conn(120000);
                fresh.authenticate(app, true);
                fresh.setTimeout(5000);
                synchronized (AdbClient.class) {
                    closeQuietly(conn);
                    conn = fresh;
                }
                prefs(app).edit().putBoolean(AUTHORIZED, true).apply();
                state = ON;
                android.util.Log.i("ArcAdb", "adb authorized and connected");
            } catch (IOException e) {
                state = OFF;
                note("enable", e);
            }
        }, "arc-adb-enable");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ using it

    /** One key press from the fixed list. */
    static void key(Context c, int code) throws IOException {
        if (!KEYS.contains(code)) {
            throw new IOException("That key is not allowed.");
        }
        shell(c, "input keyevent " + code);
    }

    /** Runs one fixed command. Callers pass constants only; nothing from a phone is ever put in the text. */
    private static synchronized String shell(Context c, String command) throws IOException {
        if (state == WAITING) {
            throw new IOException("Waiting for you to tap ALLOW on the TV.");
        }
        try {
            return runOn(connection(c), command);
        } catch (IOException first) {
            note("command", first);
            closeQuietly(conn);
            conn = null;
            if (UNAUTHORIZED.equals(first.getMessage())) {
                throw first;
            }
            return runOn(connection(c), command);
        }
    }

    private static Conn connection(Context c) throws IOException {
        if (conn != null) {
            return conn;
        }
        if (!authorized(c)) {
            throw new IOException(UNAUTHORIZED);
        }
        Conn fresh = new Conn(5000);
        try {
            fresh.authenticate(c.getApplicationContext(), false);
        } catch (IOException e) {
            closeQuietly(fresh);
            if (UNAUTHORIZED.equals(e.getMessage())) {
                prefs(c).edit().putBoolean(AUTHORIZED, false).apply();
                state = OFF;
            }
            throw e;
        }
        conn = fresh;
        state = ON;
        return fresh;
    }

    private static String runOn(Conn c, String command) throws IOException {
        return c.shell(command);
    }

    private static void note(String where, Exception e) {
        lastError = (where + ": " + e).replace('\n', ' ');
        android.util.Log.w("ArcAdb", where + ": " + e, e);
    }

    private static void closeQuietly(Conn c) {
        if (c != null) {
            c.close();
        }
    }

    // ------------------------------------------------------------------ the adb key (made here, kept here)

    private static synchronized void loadKey(Context c) throws IOException {
        if (privateKey != null) {
            return;
        }
        File f = new File(c.getFilesDir(), "adb_key.pk8");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (!f.exists()) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                KeyPair kp = g.generateKeyPair();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(kp.getPrivate().getEncoded());
                }
                f.setReadable(false, false);
                f.setReadable(true, true);
            }
            PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(f.toPath())));
            // this TV's crypto provider returns a plain RSA private key (not the CRT form), so the public half is
            // rebuilt from its modulus and the standard exponent 65537 that key generation always uses
            RSAPrivateKey rp = (RSAPrivateKey) p;
            publicKey = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(rp.getModulus(),
                    BigInteger.valueOf(65537)));
            privateKey = p;
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IOException("Could not load the adb key: " + e.getMessage());
        }
    }

    private static byte[] sign(Context c, byte[] token) throws IOException {
        loadKey(c);
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(privateKey);
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign: " + e.getMessage());
        }
    }

    /** The public key in adb's own format (a 524-byte struct, base64) plus a label, NUL-terminated. */
    private static byte[] publicKeyLine(Context c) throws IOException {
        loadKey(c);
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger inv = n.mod(r32).modInverse(r32);
        long n0inv = r32.subtract(inv).longValue() & 0xFFFFFFFFL;
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt((int) n0inv);
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(publicKey.getPublicExponent().intValue());
        String line = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " arc@tv\0";
        return line.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] padded = new byte[len];
        int copy = Math.min(be.length, len);
        System.arraycopy(be, be.length - copy, padded, len - copy, copy);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = padded[len - 1 - i];
        }
        return out;
    }

    // ------------------------------------------------------------------ one adb connection

    private static final class Conn {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private int nextId = 1;

        Conn(int timeoutMs) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), 3000);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        void setTimeout(int ms) throws IOException {
            socket.setSoTimeout(ms);
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }

        /** Connects and proves who we are; with allowPrompt it also offers the key so the TV can ask the user. */
        void authenticate(Context c, boolean allowPrompt) throws IOException {
            send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false;
            for (int i = 0; i < 6; i++) {
                Msg m = read();
                if (m.cmd == A_CNXN) {
                    return;
                }
                if (m.cmd != A_AUTH || m.arg0 != 1) {
                    throw new IOException("Unexpected reply from adb.");
                }
                if (!signed) {
                    send(A_AUTH, 2, 0, sign(c, m.data));
                    signed = true;
                } else if (allowPrompt) {
                    send(A_AUTH, 3, 0, publicKeyLine(c));
                } else {
                    throw new IOException(UNAUTHORIZED);
                }
            }
            throw new IOException("adb did not finish connecting.");
        }

        String shell(String command) throws IOException {
            int id = nextId++;
            send(A_OPEN, id, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                Msg m = read();
                if (m.arg1 != id) {
                    continue;
                }
                if (m.cmd == A_WRTE) {
                    text.write(m.data, 0, m.data.length);
                    send(A_OKAY, id, m.arg0, new byte[0]);
                } else if (m.cmd == A_CLSE) {
                    return new String(text.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }

        private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
            ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0).putInt(cmd ^ 0xFFFFFFFF);
            out.write(h.array());
            if (data.length > 0) {
                out.write(data);
            }
            out.flush();
        }

        private Msg read() throws IOException {
            ByteBuffer h = ByteBuffer.wrap(readFully(24)).order(ByteOrder.LITTLE_ENDIAN);
            Msg m = new Msg();
            m.cmd = h.getInt();
            m.arg0 = h.getInt();
            m.arg1 = h.getInt();
            int len = h.getInt();
            h.getInt();
            int magic = h.getInt();
            if (magic != (m.cmd ^ 0xFFFFFFFF) || len < 0 || len > MAX_DATA) {
                throw new IOException("Bad adb message.");
            }
            m.data = readFully(len);
            return m;
        }

        private byte[] readFully(int n) throws IOException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) {
                    throw new IOException("adb closed the connection.");
                }
                off += r;
            }
            return b;
        }
    }

    private static final class Msg {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }
}
