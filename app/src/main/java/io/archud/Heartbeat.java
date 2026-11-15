package io.archud;

import android.os.Build;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sends a small heartbeat to the ARC Oracle listener every 10 minutes: keeps the Always Free instance
 * looking active, and logs which devices this TV successfully talked to since the last one. Best-effort
 * only (fire-and-forget on a background thread); any failure is ignored and the next try is in 10 minutes
 * regardless. See LinkServer.authorized() for where contacts get recorded. The shared token comes from
 * BuildConfig (a CI secret, never committed) — empty on a local build, which just skips sending.
 */
final class Heartbeat {
    private static final String URL = "http://129.225.98.36:8443/heartbeat";
    static final long INTERVAL_MS = 10L * 60 * 1000;

    private static final Set<String> contacts = new LinkedHashSet<>();

    private Heartbeat() {
    }

    /** Called whenever another device successfully authenticates with this one. */
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
            String device = Build.MODEL == null ? "tv" : Build.MODEL.replace("\"", "");
            String body = "{\"device\":\"" + device + "\",\"type\":\"tv\",\"contacts\":" + c + "}";
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
