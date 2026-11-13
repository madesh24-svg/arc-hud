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
        return prefs(c).getString(HOST, "");
    }

    static String pin(Context c) {
        return prefs(c).getString(PIN, "");
    }

    static void save(Context c, String host, String pin) {
        prefs(c).edit().putString(HOST, host).putString(PIN, pin).apply();
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
