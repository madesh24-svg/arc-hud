package io.archud;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * BEACON: the private mapping of named devices this device can relay ARC LINK commands to (today just the
 * main TV, more later). Stored only on this device; a paired caller can ask for the NAMES (see LinkServer's
 * /beacon/list) but the addresses and PINs here are never sent to anyone.
 */
final class BeaconMap {
    static final class Entry {
        final String name;
        final String ip;
        final String pin;

        Entry(String name, String ip, String pin) {
            this.name = name;
            this.ip = ip;
            this.pin = pin;
        }
    }

    private BeaconMap() {
    }

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = Prefs.get(c).getString(Prefs.BEACON_MAP, "");
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 3 && !f[0].isEmpty()) {
                out.add(new Entry(f[0], f[1], f[2]));
            }
        }
        return out;
    }

    /** The stored entry for this name (case-insensitive), or null. */
    static Entry find(Context c, String name) {
        for (Entry e : list(c)) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    /** Adds the entry, or replaces the one with the same name (case-insensitive). */
    static void put(Context c, String name, String ip, String pin) {
        String clean = name.replace('\t', ' ').replace('\n', ' ').trim();
        List<Entry> entries = list(c);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equalsIgnoreCase(clean)) {
                entries.set(i, new Entry(clean, ip, pin));
                save(c, entries);
                return;
            }
        }
        entries.add(new Entry(clean, ip, pin));
        save(c, entries);
    }

    static void remove(Context c, String name) {
        List<Entry> entries = list(c);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equalsIgnoreCase(name)) {
                entries.remove(i);
                break;
            }
        }
        save(c, entries);
    }

    private static void save(Context c, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.name).append('\t').append(e.ip).append('\t').append(e.pin).append('\n');
        }
        Prefs.get(c).edit().putString(Prefs.BEACON_MAP, sb.toString()).apply();
    }
}
