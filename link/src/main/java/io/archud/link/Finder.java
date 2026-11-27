package io.archud.link;

import android.content.Context;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the paired TV again when the router gives it a new address. The TV announces "ARCLINK1|id|name|ip|port|mac"
 * on UDP port 8766 every few seconds; this listens while the app is running, and when the announcement carries the
 * ID of the TV this phone is paired with, the stored address is updated. Only announcements whose stated address
 * matches the sender and is a home-network address are believed. Nothing private is sent by this class.
 */
final class Finder {
    static final int PORT = 8766;
    private static final long FRESH_MS = 20_000;

    /** id to {ip, time}: the latest announcement heard from each TV. */
    private static final ConcurrentHashMap<String, String[]> SEEN = new ConcurrentHashMap<>();
    private static Thread thread;
    private static Context app;

    private Finder() {
    }

    /** Starts listening if not already; safe to call as often as needed. */
    static synchronized void ensure(Context c) {
        if (app == null) {
            app = c.getApplicationContext();
        }
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(Finder::listen, "arclink-finder");
        thread.setDaemon(true);
        thread.start();
    }

    private static void listen() {
        while (true) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                s.setSoTimeout(5000);
                byte[] buf = new byte[512];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress());
                }
            } catch (IOException | RuntimeException e) {
                // port busy or network changing: try again shortly
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void handle(String msg, InetAddress from) {
        String[] f = msg.split("\\|", -1);
        if (f.length < 6 || !"ARCLINK1".equals(f[0]) || from == null) {
            return;
        }
        String id = f[1];
        String ip = f[3];
        if (id.length() != 16 || !ip.equals(from.getHostAddress()) || !TvClient.isLocalIp(ip)) {
            return;
        }
        String screen = f.length >= 7 && "standby".equals(f[6]) ? "standby" : "on";
        SEEN.put(id, new String[] {ip, String.valueOf(System.currentTimeMillis()), screen, f[2]});
        if (app != null) {
            Store.onAnnounce(app, id, f[2], ip, f[5]);
        }
    }

    /**
     * How the paired TV looks right now, from its latest announcement: {name, state, secondsSinceHeard} where state
     * is ON, STANDBY (screen off, still reachable) or OFFLINE (not heard for 15 seconds). Null when this phone is
     * not paired or has not learned the TV's ID yet.
     */
    static String[] status(Context c) {
        String id = Store.tvId(c);
        if (id.isEmpty()) {
            return null;
        }
        String name = Store.tvName(c);
        String[] e = SEEN.get(id);
        if (e == null) {
            return new String[] {name, "OFFLINE", "-1"};
        }
        long age = (System.currentTimeMillis() - Long.parseLong(e[1])) / 1000;
        String state = age > 15 ? "OFFLINE" : "standby".equals(e[2]) ? "STANDBY" : "ON";
        return new String[] {e[3].isEmpty() ? name : e[3], state, String.valueOf(age)};
    }

    /**
     * Called when the saved address did not answer: waits up to waitMs for an announcement from the paired TV and
     * returns its current address, or null if it is not heard or has not moved.
     */
    static String waitFor(String failedHost, long waitMs) {
        Context c = app;
        if (c == null) {
            return null;
        }
        String id = Store.tvId(c);
        if (id.isEmpty()) {
            return null;
        }
        long end = System.currentTimeMillis() + waitMs;
        while (true) {
            String[] e = SEEN.get(id);
            if (e != null && System.currentTimeMillis() - Long.parseLong(e[1]) < FRESH_MS
                    && !e[0].equals(failedHost)) {
                return e[0];
            }
            if (System.currentTimeMillis() >= end) {
                return null;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
