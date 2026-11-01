package io.archud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Human-readable names for tile keys ("app:<package>", "input:<id>"), numbering duplicates. */
final class TileNames {
    private TileNames() {
    }

    static String[] labels(Context c, List<String> keys) {
        String[] out = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            out[i] = label(c, keys.get(i));
        }
        return numberDuplicates(out);
    }

    /** "AV", "AV" becomes "AV 1", "AV 2"; unique names are left alone. */
    static String[] numberDuplicates(String[] names) {
        Map<String, Integer> total = new HashMap<>();
        for (String n : names) {
            total.put(n, total.containsKey(n) ? total.get(n) + 1 : 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            if (total.get(names[i]) > 1) {
                int k = seen.containsKey(names[i]) ? seen.get(names[i]) + 1 : 1;
                seen.put(names[i], k);
                out[i] = names[i] + " " + k;
            } else {
                out[i] = names[i];
            }
        }
        return out;
    }

    private static String label(Context c, String key) {
        if (key.startsWith("app:")) {
            String pkg = key.substring(4);
            PackageManager pm = c.getPackageManager();
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return pkg + " (removed)";
            }
        }
        if (key.startsWith("input:")) {
            String id = key.substring(6);
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            TvInputInfo info = tim != null ? tim.getTvInputInfo(id) : null;
            CharSequence l = info != null ? info.loadLabel(c) : null;
            return TextUtils.isEmpty(l) ? id : l.toString();
        }
        return key;
    }
}
