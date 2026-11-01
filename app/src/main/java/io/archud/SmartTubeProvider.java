package io.archud;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import android.content.Intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search results straight from SmartTube. SmartTube answers Android TV search queries through its own
 * content provider, so this needs no network access from ARC. A result plays by sending SmartTube the
 * plain vnd.youtube://VIDEO_ID link (the video id is read from the thumbnail address).
 */
final class SmartTubeProvider implements SearchProvider {
    static final String PACKAGE = "org.smarttube.stable";
    private static final int MAX_RESULTS = 20;
    private static final Pattern VIDEO_ID = Pattern.compile("/vi/([A-Za-z0-9_-]{11})/");

    @Override
    public String heading() {
        return "SMARTTUBE";
    }

    @Override
    public int firstRows() {
        return 10;
    }

    @Override
    public List<SearchResult> search(Context context, String query) throws IOException {
        Uri uri = Uri.parse("content://" + PACKAGE + "/search/search_suggest_query/" + Uri.encode(query));
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
        } catch (RuntimeException e) {
            throw new IOException("SmartTube did not answer", e);
        }
        if (cursor == null) {
            throw new IOException("SmartTube is not installed");
        }
        List<SearchResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            int title = cursor.getColumnIndex("suggest_text_1");
            int sub = cursor.getColumnIndex("suggest_text_2");
            int image = cursor.getColumnIndex("suggest_result_card_image");
            int duration = cursor.getColumnIndex("suggest_duration");
            if (title < 0 || image < 0) {
                throw new IOException("Unexpected answer from SmartTube");
            }
            while (cursor.moveToNext() && out.size() < MAX_RESULTS) {
                String thumb = cursor.getString(image);
                String id = videoId(thumb);
                if (id == null || !seen.add(id)) {
                    continue;
                }
                String channel = sub >= 0 && !cursor.isNull(sub) ? cursor.getString(sub) : "";
                long ms = duration >= 0 && !cursor.isNull(duration) ? cursor.getLong(duration) : 0;
                String subtitle = ms > 0 ? channel + "   ·   " + clock(ms) : channel;
                Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://" + id)).setPackage(PACKAGE);
                out.add(new SearchResult(cursor.getString(title), subtitle, thumb, play, false));
            }
        } finally {
            cursor.close();
        }
        Intent all = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).setPackage(PACKAGE);
        out.add(new SearchResult("OPEN ALL RESULTS IN SMARTTUBE", "SmartTube's own search screen", null, all, true));
        return out;
    }

    private static String videoId(String thumbUrl) {
        if (thumbUrl == null) {
            return null;
        }
        Matcher m = VIDEO_ID.matcher(thumbUrl);
        return m.find() ? m.group(1) : null;
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }
}
