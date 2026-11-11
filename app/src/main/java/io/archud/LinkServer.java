package io.archud;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ARC LINK: a tiny web page served by the TV on the home network. A phone opens it in any browser,
 * enters the PIN shown in ARC SETTINGS and sends a YouTube link (opens in SmartTube) or a web address
 * (opens in the browser). Off by default; switched on in ARC SETTINGS.
 *
 * Safety: only private/loopback client addresses are served, every action needs the PIN (5 wrong tries
 * lock it for a minute), requests are size- and time-limited, and nothing is stored or sent anywhere.
 * The server lives as long as the app's process, which the home screen keeps running.
 */
final class LinkServer {
    static final int PORT = 8765;
    private static final int MAX_HEAD = 8192;
    private static final int MAX_BODY = 4096;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60_000;
    /** Largest video accepted; the TV's free storage usually limits it first. */
    private static final long MAX_VIDEO = 450L * 1024 * 1024;
    /** Space always left free on the TV after a video is stored. */
    private static final long RESERVE = 100L * 1024 * 1024;
    static final String VIDEO_PREFIX = "arclink-video-";

    private static LinkServer instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private volatile boolean closed;
    private int fails;
    private boolean uploading;
    private long lockedUntil;

    private LinkServer(Context app, ServerSocket server) {
        this.app = app;
        this.server = server;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts or stops the server so it matches the ARC LINK switch. Safe to call at any time. */
    static synchronized void sync(Context context) {
        Context app = context.getApplicationContext();
        boolean wanted = Prefs.get(app).getBoolean(Prefs.LINK_ON, false);
        if (wanted && instance == null) {
            try {
                cleanStale(app);
                ServerSocket ss = new ServerSocket(PORT);
                instance = new LinkServer(app, ss);
                Thread t = new Thread(instance::acceptLoop, "arclink-accept");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                instance = null;
            }
        } else if (!wanted && instance != null) {
            instance.close();
            instance = null;
        }
    }

    static synchronized boolean running() {
        return instance != null;
    }

    private void close() {
        closed = true;
        pool.shutdownNow();
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- PIN

    /** The 4-digit PIN, created on first use. */
    static String pin(Context c) {
        String p = Prefs.get(c).getString(Prefs.LINK_PIN, "");
        if (p.length() != 4) {
            p = newPin(c);
        }
        return p;
    }

    static String newPin(Context c) {
        String p = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
        Prefs.get(c).edit().putString(Prefs.LINK_PIN, p).apply();
        return p;
    }

    /** What to type into the phone's browser, or null when the TV has no local address. */
    static String address() {
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
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (IOException ignored) {
            // no address
        }
        return null;
    }

    /** 0 = accepted; otherwise the HTTP status to answer with (403 wrong PIN, 429 locked). */
    private synchronized int check(String given) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            return 429;
        }
        String expected = pin(app);
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            fails = 0;
            return 0;
        }
        if (++fails >= MAX_FAILS) {
            fails = 0;
            lockedUntil = now + LOCK_MS;
        }
        return 403;
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            if (!NetPolicy.isLocal(s.getInetAddress())) {
                return;
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                reply(s, 400, "text/plain", "Bad request");
                return;
            }
            long length = 0;
            long size = 0;
            String pin = null;
            String fileName = null;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String name = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
                String value = lines[i].substring(c + 1).trim();
                if ("content-length".equals(name)) {
                    try {
                        length = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        length = -1;
                    }
                } else if ("x-pin".equals(name)) {
                    pin = value;
                } else if ("x-name".equals(name)) {
                    fileName = value;
                } else if ("x-size".equals(name)) {
                    try {
                        size = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        size = -1;
                    }
                }
            }
            String method = request[0];
            String path = request[1];
            if ("GET".equals(method) && ("/".equals(path) || path.startsWith("/?"))) {
                reply(s, 200, "text/html; charset=utf-8", page());
            } else if ("POST".equals(method) && "/open".equals(path)) {
                if (length < 0 || length > MAX_BODY) {
                    reply(s, 400, "text/plain", "Too long.");
                    return;
                }
                int gate = check(pin);
                if (gate == 429) {
                    reply(s, 429, "text/plain", "Too many wrong PINs. Wait a minute.");
                    return;
                }
                if (gate != 0) {
                    reply(s, 403, "text/plain", "Wrong PIN.");
                    return;
                }
                String body = readBody(in, (int) length);
                open(s, form(body, "url"));
            } else if ("POST".equals(method) && ("/videocheck".equals(path) || "/video".equals(path))) {
                int gate = check(pin);
                if (gate == 429) {
                    reply(s, 429, "text/plain", "Too many wrong PINs. Wait a minute.");
                    return;
                }
                if (gate != 0) {
                    reply(s, 403, "text/plain", "Wrong PIN.");
                    return;
                }
                if ("/videocheck".equals(path)) {
                    String problem = videoRoom(size);
                    reply(s, problem == null ? 200 : 413, "text/plain", problem == null ? "OK" : problem);
                } else {
                    upload(s, in, length, fileName);
                }
            } else if ("POST".equals(method) && "/play".equals(path)) {
                if (length < 0 || length > MAX_BODY) {
                    reply(s, 400, "text/plain", "Too long.");
                    return;
                }
                int gate = check(pin);
                if (gate == 429) {
                    reply(s, 429, "text/plain", "Too many wrong PINs. Wait a minute.");
                    return;
                }
                if (gate != 0) {
                    reply(s, 403, "text/plain", "Wrong PIN.");
                    return;
                }
                play(s, readBody(in, (int) length));
            } else {
                reply(s, 404, "text/plain", "Not found");
            }
        } catch (IOException | RuntimeException e) {
            // a bad or dropped connection is simply ignored
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < MAX_HEAD) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
            } else {
                state = b == '\r' ? 1 : 0;
            }
            if (state == 4) {
                return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static String readBody(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(data, got, length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        return new String(data, 0, got, StandardCharsets.UTF_8);
    }

    private static String form(String body, String key) {
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                } catch (IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    private String page() throws IOException {
        try (InputStream in = app.getAssets().open("arclink.html")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void reply(Socket s, int code, String type, String body) throws IOException {
        String status = code == 200 ? "OK" : (code == 400 ? "Bad Request" : (code == 403 ? "Forbidden"
                : (code == 404 ? "Not Found" : (code == 429 ? "Too Many Requests" : (code == 413 ? "Payload Too Large" : "Error")))));
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + data.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    // ---------------------------------------------------------------- video upload

    /** Deletes video files left behind earlier, except the one the player is showing. */
    static void cleanStale(Context c) {
        File[] files = c.getCacheDir().listFiles();
        if (files == null) {
            return;
        }
        String playing = PlayerActivity.current();
        for (File f : files) {
            if (f.getName().startsWith(VIDEO_PREFIX) && !f.getAbsolutePath().equals(playing)) {
                f.delete();
            }
        }
    }

    /** null = a video of this size can be stored now; otherwise what to tell the phone. */
    private String videoRoom(long size) {
        if (size <= 0) {
            return "Empty or unknown file size.";
        }
        if (size > MAX_VIDEO) {
            return "Too big. The TV can take about " + (MAX_VIDEO / 1048576) + " MB at most.";
        }
        synchronized (this) {
            if (uploading) {
                return "Another upload is still running.";
            }
            cleanStale(app);
        }
        long free = app.getCacheDir().getUsableSpace();
        if (size + RESERVE > free) {
            return "Not enough free space on the TV (" + (free / 1048576) + " MB free). "
                    + "Press BACK on the TV to close the current video, or send a smaller file.";
        }
        return null;
    }

    private static String videoExtension(String rawName) {
        String name = decode(rawName);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        for (String ok : new String[] {"mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts"}) {
            if (ok.equals(ext)) {
                return ext;
            }
        }
        return "mp4";
    }

    private static String decode(String raw) {
        if (raw == null) {
            return "video";
        }
        try {
            String s = URLDecoder.decode(raw, "UTF-8");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < s.length() && out.length() < 80; i++) {
                char ch = s.charAt(i);
                out.append(ch < 32 ? ' ' : ch);
            }
            return out.length() == 0 ? "video" : out.toString();
        } catch (IOException | RuntimeException e) {
            return "video";
        }
    }

    private void upload(Socket s, InputStream in, long length, String rawName) throws IOException {
        String problem = videoRoom(length);
        if (problem != null) {
            reply(s, 413, "text/plain", problem);
            return;
        }
        synchronized (this) {
            if (uploading) {
                reply(s, 413, "text/plain", "Another upload is still running.");
                return;
            }
            uploading = true;
        }
        long stamp = System.currentTimeMillis();
        File part = new File(app.getCacheDir(), VIDEO_PREFIX + stamp + ".part");
        File done = new File(app.getCacheDir(), VIDEO_PREFIX + stamp + "." + videoExtension(rawName));
        boolean ok = false;
        try {
            s.setSoTimeout(30000);
            try (FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    left -= n;
                }
                if (left > 0) {
                    throw new IOException("upload ended early");
                }
            }
            if (!part.renameTo(done)) {
                throw new IOException("could not store the file");
            }
            ok = true;
            app.startActivity(new Intent(app, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_FILE, done.getAbsolutePath())
                    .putExtra(PlayerActivity.EXTRA_TITLE, decode(rawName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            reply(s, 200, "text/plain", "Playing on the TV.");
        } catch (IOException e) {
            part.delete();
            try {
                reply(s, 500, "text/plain", "Upload failed: " + e.getMessage());
            } catch (IOException ignored) {
                // the phone has already gone
            }
        } finally {
            synchronized (this) {
                uploading = false;
            }
            if (!ok) {
                part.delete();
            }
        }
    }

    // ---------------------------------------------------------------- video that stays on the phone

    /**
     * The ARC phone app asks the TV to play a file it serves itself. The TV only ever connects back to
     * the address that sent this request, on the port and path given, with the token given.
     */
    private void play(Socket s, String body) throws IOException {
        String port = form(body, "port");
        String path = form(body, "path");
        String token = form(body, "token");
        int portNumber;
        try {
            portNumber = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            portNumber = 0;
        }
        if (portNumber < 1024 || portNumber > 65535 || !path.matches("/[A-Za-z0-9/_.~%-]{1,200}")
                || !token.matches("[A-Za-z0-9]{8,64}")) {
            reply(s, 400, "text/plain", "Bad play request.");
            return;
        }
        Intent play = new Intent(app, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_HOST, s.getInetAddress().getHostAddress())
                .putExtra(PlayerActivity.EXTRA_PORT, portNumber)
                .putExtra(PlayerActivity.EXTRA_PATH, path)
                .putExtra(PlayerActivity.EXTRA_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_TITLE, decode(form(body, "title")));
        if ("ts".equals(form(body, "mode"))) {
            // the phone repackages the video as a stream it can start anywhere (see PhoneStream)
            long durationMs = number(form(body, "duration"), 0, 1000L * 3600 * 100);
            if (durationMs > 0) {
                play.putExtra(PlayerActivity.EXTRA_MODE, "ts")
                        .putExtra(PlayerActivity.EXTRA_DURATION, durationMs)
                        .putExtra(PlayerActivity.EXTRA_BPS, number(form(body, "bps"), 0, 1L << 30))
                        .putExtra(PlayerActivity.EXTRA_NOTE, decode(form(body, "note")));
            }
        }
        app.startActivity(play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        reply(s, 200, "text/plain", "Playing on the TV.");
    }

    /** A whole number from text, kept between 0 and max (0 when it is not a number). */
    private static long number(String text, long min, long max) {
        try {
            return Math.max(min, Math.min(max, Long.parseLong(text.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- opening links

    private void open(Socket s, String raw) throws IOException {
        String link = raw;
        if (link.isEmpty() || link.length() > 2000 || link.indexOf(' ') >= 0) {
            reply(s, 400, "text/plain", "That does not look like a link.");
            return;
        }
        String lower = link.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (link.contains("://") || link.indexOf('.') < 0) {
                reply(s, 400, "text/plain", "Only web and YouTube links work.");
                return;
            }
            link = "https://" + link;
        }
        String id = youtubeId(link);
        try {
            if (id != null) {
                app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://" + id))
                        .setPackage(SmartTubeProvider.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("ARC LINK: playing in SmartTube");
                reply(s, 200, "text/plain", "Playing on the TV in SmartTube.");
            } else {
                app.startActivity(Browsers.view(app, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("ARC LINK: opening in the browser");
                reply(s, 200, "text/plain", "Opening in the TV browser.");
            }
        } catch (ActivityNotFoundException e) {
            reply(s, 502, "text/plain", id != null ? "SmartTube is not installed on the TV."
                    : "The TV has no app that can open this.");
        }
    }

    /** The 11-character video id of a YouTube watch, share, shorts, live or embed link, else null. */
    static String youtubeId(String link) {
        Uri u = Uri.parse(link);
        String host = u.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.US);
        String id = null;
        if (host.equals("youtu.be")) {
            id = u.getPathSegments().isEmpty() ? null : u.getPathSegments().get(0);
        } else if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            java.util.List<String> seg = u.getPathSegments();
            if (!seg.isEmpty() && seg.get(0).equals("watch")) {
                id = u.getQueryParameter("v");
            } else if (seg.size() >= 2 && (seg.get(0).equals("shorts") || seg.get(0).equals("live")
                    || seg.get(0).equals("embed"))) {
                id = seg.get(1);
            }
        }
        return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
    }

    private void toast(String text) {
        main.post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
