package io.archud;

import android.content.Context;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * The photos a phone has sent for ARC PHOTOS: a few TV-sized JPEG files in the app's private cache,
 * numbered by their place in the phone's album, plus which one is on screen. Only photos near the one
 * on screen are kept, so the TV never holds more than a handful. All files are removed when the viewer closes.
 */
final class ImageStore {
    static final String PREFIX = "arclink-img-";
    /** Photos further than this many places (around the album, counting past the end) are deleted. */
    private static final int KEEP = 4;

    private static String session = "";
    private static final TreeMap<Integer, File> files = new TreeMap<>();
    private static int pos = 1;
    private static int total = 1;
    private static boolean open;

    private ImageStore() {
    }

    static File file(Context c, String sessionId, int place) {
        return new File(c.getCacheDir(), PREFIX + sessionId + "-" + place + ".jpg");
    }

    /** Records a photo that has just been written. A new session replaces everything from the old one. */
    static synchronized void add(String sessionId, int place, int count, File f) {
        if (!sessionId.equals(session)) {
            deleteAll();
            session = sessionId;
            pos = place;
        }
        files.put(place, f);
        total = count;
    }

    /** Makes a stored photo the one on screen. False when that photo is not on the TV (yet). */
    static synchronized boolean show(String sessionId, int place, int count) {
        if (!sessionId.equals(session) || !files.containsKey(place)) {
            return false;
        }
        pos = place;
        total = count;
        prune();
        return true;
    }

    /** Moves to the previous or next photo when it is already on the TV. */
    static synchronized boolean step(int delta) {
        int n = pos + delta;
        if (n < 1 || n > total || !files.containsKey(n)) {
            return false;
        }
        pos = n;
        prune();
        return true;
    }

    static synchronized File currentFile() {
        return files.get(pos);
    }

    static synchronized int pos() {
        return pos;
    }

    static synchronized int total() {
        return total;
    }

    static synchronized String session() {
        return session;
    }

    static synchronized void setOpen(boolean value) {
        open = value;
    }

    static synchronized boolean isOpen() {
        return open;
    }

    /** What the phone polls: "none" or "session place total". */
    static synchronized String state() {
        return open ? session + " " + pos + " " + total : "none";
    }

    static synchronized void clear() {
        deleteAll();
        session = "";
    }

    private static void deleteAll() {
        for (File f : files.values()) {
            f.delete();
        }
        files.clear();
    }

    private static void prune() {
        Iterator<Map.Entry<Integer, File>> it = files.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, File> e = it.next();
            int d = Math.abs(e.getKey() - pos);
            d = Math.min(d, Math.max(total - d, 0));
            if (d > KEEP) {
                e.getValue().delete();
                it.remove();
            }
        }
    }
}
