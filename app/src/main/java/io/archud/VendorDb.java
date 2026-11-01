package io.archud;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Manufacturer names from the MAC prefix (OUI). The table is built by CI from the public OUI list and
 * published with this app's own releases; the device downloads it from this repo only (weekly) and
 * looks names up locally, so MAC addresses never leave the network.
 */
final class VendorDb {
    static final String ASSET = "oui.tsv.gz";
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private VendorDb() {
    }

    /** Network + disk work: call off the main thread. Returns names for the requested 6-hex prefixes. */
    static Map<String, String> lookup(Context c, Set<String> prefixes) {
        Map<String, String> found = new HashMap<>();
        if (prefixes.isEmpty()) {
            return found;
        }
        File db = new File(c.getFilesDir(), ASSET);
        if (!db.exists() || System.currentTimeMillis() - db.lastModified() > MAX_AGE_MS) {
            try {
                Updater.downloadAsset(ASSET, db);
            } catch (IOException ignored) {
                // keep using the old copy (or none) until the next attempt
            }
        }
        if (!db.exists()) {
            return found;
        }
        Set<String> wanted = new HashSet<>(prefixes);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(db)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && !wanted.isEmpty()) {
                int tab = line.indexOf('\t');
                if (tab != 6) {
                    continue;
                }
                String key = line.substring(0, 6);
                if (wanted.remove(key)) {
                    found.put(key, line.substring(7));
                }
            }
        } catch (IOException ignored) {
            // a corrupt copy is replaced on the next weekly refresh
        }
        return found;
    }
}
