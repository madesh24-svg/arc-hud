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
}
