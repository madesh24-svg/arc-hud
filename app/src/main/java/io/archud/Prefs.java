package io.archud;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** On-device settings. Nothing here is ever sent anywhere. */
final class Prefs {
    static final String TITLE = "title";
    static final String SUBTITLE = "subtitle";
    static final String SALUTATION = "salutation";
    static final String SSID = "ssid";
    static final String MAC = "mac";
    static final String SHOW_STATUS = "show_status";
    static final String ANIM = "anim";
    static final String HIDDEN = "hidden";
    static final String FAVS = "fav";
    static final String NEVER_ASK = "ss_never";

    static final String DEFAULT_TITLE = "A.R.C.  H.U.D.";
    static final String DEFAULT_SUBTITLE = "ADAPTIVE RESPONSE CONSOLE";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(HudActivity.PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(get(c).getStringSet(HIDDEN, new HashSet<String>()));
    }

    static void setHidden(Context c, Set<String> keys) {
        get(c).edit().putStringSet(HIDDEN, new HashSet<>(keys)).apply();
    }

    static List<String> favs(Context c) {
        List<String> out = new ArrayList<>();
        for (String k : get(c).getString(FAVS, "").split("\n")) {
            if (!k.isEmpty()) {
                out.add(k);
            }
        }
        return out;
    }

    static void setFavs(Context c, List<String> keys) {
        get(c).edit().putString(FAVS, android.text.TextUtils.join("\n", keys)).apply();
    }

    /** Speed multiplier for the ring animation: 1 normal, 0.4 slow, 0 off. */
    static float animSpeed(Context c) {
        String a = get(c).getString(ANIM, "normal");
        return "off".equals(a) ? 0f : ("slow".equals(a) ? 0.4f : 1f);
    }
}
