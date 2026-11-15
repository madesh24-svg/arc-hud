package io.archud.link;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sends a small heartbeat to the ARC Oracle listener every 10 minutes: keeps the Always Free instance
 * looking active, and logs which TVs/Beacons this phone successfully talked to since the last one.
 * Best-effort only; any failure is ignored and the next try is in 10 minutes regardless. See TvClient for
 * where contacts get recorded. The shared token comes from BuildConfig (a CI secret, never committed) —
 * empty on a local build, which just skips sending.
 */
final class Heartbeat {
    private static final String URL = "http://129.225.98.36:8443/heartbeat";
    static final long INTERVAL_MS = 10L * 60 * 1000;

    private static final Set<String> contacts = new LinkedHashSet<>();

    private Heartbeat() {
    }

    /** Called whenever this phone successfully reaches a TV or Beacon. */
    static synchronized void noteContact(String who) {
        if (who != null && contacts.size() < 50) {
            contacts.add(who);
        }
    }

    private static synchronized String[] drainContacts() {
        String[] out = contacts.toArray(new String[0]);
        contacts.clear();
        return out;
    }

    /** Starts (or re-times) the repeating alarm; idempotent, safe to call every time the app opens. */
    static void schedule(Context context) {
        if (BuildConfig.HEARTBEAT_TOKEN.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(app, HeartbeatReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
    }

    static void sendInBackground() {
        if (BuildConfig.HEARTBEAT_TOKEN.isEmpty()) {
            return;
        }
        new Thread(Heartbeat::send, "arc-heartbeat").start();
    }

    private static void send() {
        try {
            String[] who = drainContacts();
            StringBuilder c = new StringBuilder("[");
            for (int i = 0; i < who.length; i++) {
                if (i > 0) {
                    c.append(',');
                }
                c.append('"').append(who[i].replace("\"", "")).append('"');
            }
            c.append(']');
            String device = Build.MODEL == null ? "phone" : Build.MODEL.replace("\"", "");
            String body = "{\"device\":\"" + device + "\",\"type\":\"phone\",\"contacts\":" + c + "}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("X-Token", BuildConfig.HEARTBEAT_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
            // best-effort: nothing to retry, the next attempt is in INTERVAL_MS regardless
        }
    }
}
