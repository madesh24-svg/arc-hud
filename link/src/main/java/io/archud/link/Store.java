package io.archud.link;

import android.content.Context;
import android.content.SharedPreferences;

/** The paired TV (its local address and PIN), kept on this phone only. */
final class Store {
    private static final String FILE = "arclink";
    private static final String HOST = "host";
    private static final String PIN = "pin";

    private Store() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String host(Context c) {
        Finder.ensure(c);
        return prefs(c).getString(HOST, "");
    }

    /** The paired TV's permanent ID (learned from its announcements), or an empty string if not known yet. */
    static String tvId(Context c) {
        return prefs(c).getString("tv_id", "");
    }

    /** The paired TV's hardware address, kept for waking it later; empty if the TV did not report one. */
    static String tvMac(Context c) {
        return prefs(c).getString("tv_mac", "");
    }

    static String tvName(Context c) {
        return prefs(c).getString("tv_name", "");
    }

    /** Makes this announcing TV the paired one (its PIN was just accepted at that address). */
    static synchronized void adopt(Context c, String id, String name, String ip, String mac) {
        prefs(c).edit().putString(HOST, ip).putString("tv_id", id).putString("tv_name", name)
                .putString("tv_mac", mac).apply();
    }

    /**
     * An announcement from a TV on the network. If this phone has no TV ID yet and the announcement comes from the
     * address it is paired with, that TV's ID is adopted; if the ID matches the paired TV, the stored address
     * follows it to wherever the router put it.
     */
    static synchronized void onAnnounce(Context c, String id, String name, String ip, String mac) {
        SharedPreferences p = prefs(c);
        String host = p.getString(HOST, "");
        if (host.isEmpty()) {
            return;
        }
        String mine = p.getString("tv_id", "");
        if (mine.isEmpty()) {
            if (!host.equals(ip)) {
                return;
            }
            p.edit().putString("tv_id", id).putString("tv_name", name).putString("tv_mac", mac).apply();
        } else if (mine.equals(id)) {
            SharedPreferences.Editor e = p.edit();
            if (!host.equals(ip)) {
                e.putString(HOST, ip);
            }
            e.putString("tv_name", name);
            if (!mac.isEmpty()) {
                e.putString("tv_mac", mac);
            }
            e.apply();
        }
    }

    static String pin(Context c) {
        return prefs(c).getString(PIN, "");
    }

    static void save(Context c, String host, String pin) {
        boolean sameTv = host.equals(prefs(c).getString(HOST, ""));
        SharedPreferences.Editor e = prefs(c).edit().putString(HOST, host).putString(PIN, pin);
        if (!sameTv) {
            // a different TV: forget the old one's ID until its own announcement is heard
            e.remove("tv_id").remove("tv_name").remove("tv_mac");
        }
        e.apply();
    }

    static boolean paired(Context c) {
        return !host(c).isEmpty() && !pin(c).isEmpty();
    }

    // ---- Jarvis (voice) settings, all off until the user turns them on

    static boolean voiceOn(Context c) {
        return prefs(c).getBoolean("voice_on", false);
    }

    static void setVoiceOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("voice_on", on).apply();
    }

    static boolean spoken(Context c) {
        return prefs(c).getBoolean("voice_spoken", true);
    }

    static void setSpoken(Context c, boolean on) {
        prefs(c).edit().putBoolean("voice_spoken", on).apply();
    }

    /** Minutes without a command before Jarvis goes back to sleep; 0 = never (until the app is closed). */
    static int sleepMinutes(Context c) {
        return prefs(c).getInt("voice_sleep_min", 0);
    }

    static void setSleepMinutes(Context c, int minutes) {
        prefs(c).edit().putInt("voice_sleep_min", minutes).apply();
    }
}
