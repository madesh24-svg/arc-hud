package io.archud.link;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/** Looks for a spoken song name among the songs on this phone (every word must be in the title or artist). */
final class LocalSongs {
    private LocalSongs() {
    }

    /** The words worth matching: lower case, no tiny filler words. */
    static List<String> words(String spoken) {
        List<String> out = new ArrayList<>();
        for (String w : JarvisBrain.normalize(spoken).split(" ")) {
            if (w.length() >= 2 && !"the".equals(w) && !"of".equals(w) && !"and".equals(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static boolean canRead(Context c) {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when at least one song on the phone has all of these words in its title or artist. */
    static boolean has(Context c, String spoken) {
        List<String> words = words(spoken);
        if (words.isEmpty() || !canRead(c)) {
            return false;
        }
        StringBuilder where = new StringBuilder(MediaStore.Audio.Media.DURATION + " >= 30000");
        String[] args = new String[words.size() * 2];
        for (int i = 0; i < words.size(); i++) {
            where.append(" AND (LOWER(").append(MediaStore.Audio.Media.TITLE).append(") LIKE ? OR LOWER(")
                    .append(MediaStore.Audio.Media.ARTIST).append(") LIKE ?)");
            args[i * 2] = "%" + words.get(i) + "%";
            args[i * 2 + 1] = "%" + words.get(i) + "%";
        }
        try (Cursor cursor = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Audio.Media._ID}, where.toString(), args, null)) {
            return cursor != null && cursor.getCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
