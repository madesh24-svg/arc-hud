package io.archud;

import android.content.Context;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * The songs a phone has sent for ARC AUDIO: a few audio files (and their small cover pictures) in the app's
 * private cache, numbered by their place in the phone's queue, plus which one is playing. Only the playing
 * song, the one before it and the next few are kept. All files are removed when the player closes.
 */
final class AudioStore {
    static final String PREFIX = "arclink-aud-";
    private static final int KEEP_BEHIND = 1;
    private static final int KEEP_AHEAD = 5;

    static final class Track {
        final File song;
        final String title;
        final String artist;
        File cover;

        Track(File song, String title, String artist) {
            this.song = song;
            this.title = title;
            this.artist = artist;
        }
    }

    private static String session = "";
    private static final TreeMap<Integer, Track> tracks = new TreeMap<>();
    private static int pos = 1;
    private static int total = 1;
    private static boolean open;
    private static boolean playing;
    private static int posSec;
    private static int lengthSec;

    private AudioStore() {
    }

    static File file(Context c, String sessionId, int place, String ext) {
        return new File(c.getCacheDir(), PREFIX + sessionId + "-" + place + "." + ext);
    }

    /** Records a song that has just been written. A new session replaces everything from the old one. */
    static synchronized void add(String sessionId, int place, int count, Track t) {
        if (!sessionId.equals(session)) {
            deleteAll();
            session = sessionId;
            pos = place;
        }
        Track old = tracks.put(place, t);
        if (old != null) {
            delete(old);
        }
        total = count;
    }

    static synchronized boolean setCover(String sessionId, int place, File f) {
        Track t = tracks.get(place);
        if (t == null || !sessionId.equals(session)) {
            return false;
        }
        t.cover = f;
        return true;
    }

    /** Makes a stored song the current one. False when it is not on the TV (yet). */
    static synchronized boolean show(String sessionId, int place, int count) {
        if (!sessionId.equals(session) || !tracks.containsKey(place)) {
            return false;
        }
        pos = place;
        total = count;
        prune();
        return true;
    }

    /** Moves to the previous or next song when it is already on the TV. */
    static synchronized boolean step(int delta) {
        int n = pos + delta;
        if (n < 1 || n > total || !tracks.containsKey(n)) {
            return false;
        }
        pos = n;
        prune();
        return true;
    }

    static synchronized Track current() {
        return tracks.get(pos);
    }

    static synchronized int pos() {
        return pos;
    }

    static synchronized int total() {
        return total;
    }

    static synchronized void setOpen(boolean value) {
        open = value;
        if (!value) {
            playing = false;
            posSec = 0;
            lengthSec = 0;
        }
    }

    static synchronized boolean isOpen() {
        return open;
    }

    static synchronized void setPlayback(boolean isPlaying, int seconds, int length) {
        playing = isPlaying;
        posSec = seconds;
        lengthSec = length;
    }

    /** What the phone polls: "none" or "session place total playing seconds length". */
    static synchronized String state() {
        return open ? session + " " + pos + " " + total + " " + (playing ? 1 : 0) + " " + posSec + " " + lengthSec
                : "none";
    }

    static synchronized void clear() {
        deleteAll();
        session = "";
    }

    private static void deleteAll() {
        for (Track t : tracks.values()) {
            delete(t);
        }
        tracks.clear();
    }

    private static void delete(Track t) {
        t.song.delete();
        if (t.cover != null) {
            t.cover.delete();
        }
    }

    private static void prune() {
        Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> e = it.next();
            int d = e.getKey() - pos;
            if (d < -KEEP_BEHIND || d > KEEP_AHEAD) {
                delete(e.getValue());
                it.remove();
            }
        }
    }
}
