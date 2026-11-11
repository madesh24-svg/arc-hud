package io.archud.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps one video on this phone available to the TV. It listens on a random port, answers only the TV
 * that was paired, only with the one-time token, and supports byte ranges so the TV can seek. It runs as
 * a foreground service (with a notification) so the stream keeps going with the screen off, and stops
 * itself after a long idle time or when you press Stop.
 */
public class ServeService extends Service {
    static final String ACTION_STOP = "io.archud.link.STOP";

    private static final String CHANNEL = "serve";
    private static final int NOTIFICATION_ID = 1;
    private static final String PATH = "/v/video";
    private static final long IDLE_STOP_MS = 90L * 60 * 1000;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile Uri uri;
    private volatile String token = "";
    private volatile String tvHost = "";
    private volatile String mime = "video/mp4";
    private volatile long lastActivity;
    /** What the phone found in the current video (null until looked at). */
    private volatile TsSession.Info info;
    private final AtomicInteger streamGen = new AtomicInteger();
    private volatile Socket streamSocket;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastActivity > IDLE_STOP_MS) {
                stopSelf();
            } else {
                main.postDelayed(this, 60_000);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction()) || intent.getData() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final Uri video = intent.getData();
        String name = displayName(video);
        enterForeground(NOTIFICATION_ID, notification(name));

        final String host = Store.host(this);
        final String pin = Store.pin(this);
        if (!TvClient.isLocalIp(host) || pin.isEmpty()) {
            toast("Pair with the TV first (open ARC LINK).");
            stopSelf();
            return START_NOT_STICKY;
        }
        tvHost = host;
        uri = video;
        mime = mimeOf(video);
        token = randomToken();
        lastActivity = System.currentTimeMillis();
        acquireLocks();

        final int port;
        try {
            port = ensureServer();
        } catch (IOException e) {
            toast("Could not start sharing: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        final String title = name;
        pool.execute(() -> {
            try {
                TsSession.Info found = TsSession.probe(this, video);
                info = found;
                StringBuilder form = new StringBuilder();
                form.append("port=").append(port).append("&path=").append(URLEncoder.encode(PATH, "UTF-8"))
                        .append("&token=").append(token).append("&title=").append(URLEncoder.encode(title, "UTF-8"));
                if (found.ok) {
                    // the phone repackages this video as a stream the TV can start anywhere
                    long size = fileSize(video);
                    long bytesPerSecond = size > 0 ? size * 1000 / found.durationMs : 0;
                    form.append("&mode=ts&duration=").append(found.durationMs).append("&bps=").append(bytesPerSecond);
                    if (!found.note.isEmpty()) {
                        form.append("&note=").append(URLEncoder.encode(found.note, "UTF-8"));
                    }
                }
                TvClient.Reply r = TvClient.post(host, "/play", pin, form.toString());
                toast(r.ok() ? "Playing on the TV." : (r.text.isEmpty() ? "The TV said " + r.code : r.text));
                if (!r.ok()) {
                    main.post(this::stopSelf);
                }
            } catch (IOException e) {
                toast("Could not reach the TV: " + e.getMessage());
                main.post(this::stopSelf);
            }
        });
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, 60_000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        if (wifi != null && wifi.isHeld()) {
            wifi.release();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- foreground notification

    private Notification notification(String name) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sharing to the TV",
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, ServeService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("ARC LINK is sharing to the TV")
                .setContentText(name)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopIntent).build());
        return b.build();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    private void enterForeground(int id, Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            super.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            super.startForeground(id, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arclink:serve");
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "arclink:serve");
        }
        if (!wake.isHeld()) {
            wake.acquire(3L * 60 * 60 * 1000);
        }
        if (!wifi.isHeld()) {
            wifi.acquire();
        }
    }

    private void toast(String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- file metadata

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall through to a plain name
        }
        return "video";
    }

    private String mimeOf(Uri u) {
        String type = getContentResolver().getType(u);
        return type != null && type.startsWith("video/") ? type : "video/mp4";
    }

    private static String randomToken() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the file server

    private synchronized int ensureServer() throws IOException {
        if (server == null || server.isClosed()) {
            server = new ServerSocket(0);
            final ServerSocket mine = server;
            Thread t = new Thread(() -> acceptLoop(mine), "arclink-serve");
            t.setDaemon(true);
            t.start();
        }
        return server.getLocalPort();
    }

    private void acceptLoop(ServerSocket mine) {
        while (!mine.isClosed()) {
            try {
                final Socket s = mine.accept();
                pool.execute(() -> handle(s));
            } catch (IOException | RuntimeException e) {
                return;
            }
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30000);
            if (!s.getInetAddress().getHostAddress().equals(tvHost)) {
                return;
            }
            String head = readHead(s.getInputStream());
            if (head == null) {
                return;
            }
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2 || !"GET".equals(request[0])) {
                respond(s, "404 Not Found", null);
                return;
            }
            String target = request[1];
            int q = target.indexOf('?');
            String route = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? "" : target.substring(q + 1);
            String range = null;
            String auth = "";
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String name = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
                String value = lines[i].substring(c + 1).trim();
                if ("range".equals(name)) {
                    range = value;
                } else if ("authorization".equals(name)) {
                    auth = value;
                }
            }
            if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                respond(s, "403 Forbidden", null);
                return;
            }
            lastActivity = System.currentTimeMillis();
            if ("/s".equals(route)) {
                serveStream(s, query);
            } else if (PATH.equals(route)) {
                serve(s, range);
            } else {
                respond(s, "404 Not Found", null);
            }
        } catch (IOException | RuntimeException e) {
            // the TV closed the connection (seek or stop): normal
        }
    }

    private long fileSize(Uri u) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r")) {
            return pfd.getStatSize();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * The TV asks for the video as a stream starting at a time (start=milliseconds, audio=0 for picture
     * only, used while scanning). A newer request replaces an older one, which is how seeking works.
     */
    private void serveStream(Socket s, String query) throws IOException {
        TsSession.Info found = info;
        if (found == null || !found.ok) {
            respond(s, "404 Not Found", null);
            return;
        }
        long startMs = 0;
        boolean withAudio = true;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            try {
                if ("start".equals(key)) {
                    startMs = Math.max(0, Long.parseLong(value));
                } else if ("audio".equals(key)) {
                    withAudio = !"0".equals(value);
                }
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        final int mine = streamGen.incrementAndGet();
        Socket previous = streamSocket;
        streamSocket = s;
        if (previous != null && previous != s) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
        TsSession session = null;
        boolean started = false;
        try {
            session = new TsSession(this, uri, found, startMs, withAudio);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nX-Start-Ms: " + session.startMs
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            started = true;
            session.stream(out, () -> {
                lastActivity = System.currentTimeMillis();
                return mine != streamGen.get() || s.isClosed();
            });
            out.flush();
        } catch (IOException e) {
            if (!started) {
                respond(s, "500 Internal Server Error", null);
            }
        } finally {
            if (session != null) {
                session.close();
            }
            if (streamSocket == s) {
                streamSocket = null;
            }
        }
    }

    private void serve(Socket s, String range) throws IOException {
        ContentResolver cr = getContentResolver();
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            long total = pfd.getStatSize();
            if (total <= 0) {
                respond(s, "500 Internal Server Error", null);
                return;
            }
            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) {
                        long suffix = Long.parseLong(spec.substring(1));
                        start = Math.max(0, total - suffix);
                    } else if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash));
                        if (dash < spec.length() - 1) {
                            end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                        }
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    partial = false;
                    start = 0;
                    end = total - 1;
                }
            }
            if (start >= total || start > end) {
                respond(s, "416 Range Not Satisfiable", "Content-Range: bytes */" + total);
                return;
            }
            long length = end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/')
                        .append(total).append("\r\n");
            }
            h.append("Content-Length: ").append(length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            FileChannel channel = in.getChannel();
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long pos = start;
            long left = length;
            while (left > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), left));
                int n = channel.read(buf, pos);
                if (n <= 0) {
                    break;
                }
                out.write(buf.array(), 0, n);
                pos += n;
                left -= n;
                lastActivity = System.currentTimeMillis();
            }
            out.flush();
        }
    }

    private static void respond(Socket s, String status, String extraHeader) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n" + (extraHeader == null ? "" : extraHeader + "\r\n")
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < 8192) {
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
}
