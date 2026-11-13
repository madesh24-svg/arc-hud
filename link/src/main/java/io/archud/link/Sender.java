package io.archud.link;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the phone sends to the TV: a link (opened by ARC HUD) or a video (served from this phone). */
final class Sender {
    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private Sender() {
    }

    /** Plays a video that stays on this phone; the service tells the TV where to read it. */
    static void video(Context c, Uri uri) {
        Intent service = new Intent(c, ServeService.class)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startForegroundService(service);
    }

    /** Opens the photo pager for these photos; the pager shrinks them and sends them to the TV. */
    static void photos(Context c, List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            say(c, "No photos found in that.");
            return;
        }
        ClipData clip = ClipData.newRawUri("photos", uris.get(0));
        for (int i = 1; i < uris.size() && i < PhotoActivity.MAX_PHOTOS; i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        c.startActivity(new Intent(c, PhotoActivity.class)
                .setClipData(clip)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** First web address inside shared text, or null. */
    static String firstLink(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = LINK.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Sends a YouTube or web link; the TV opens it in SmartTube or the browser. */
    static void link(Context c, String text) {
        final Context app = c.getApplicationContext();
        final String link = firstLink(text);
        if (link == null) {
            say(app, "No web link found in that.");
            return;
        }
        final String host = Store.host(app);
        final String pin = Store.pin(app);
        new Thread(() -> {
            try {
                TvClient.Reply r = TvClient.post(host, "/open", pin, "url=" + URLEncoder.encode(link, "UTF-8"));
                say(app, r.text.isEmpty() ? "The TV said " + r.code : r.text);
            } catch (IOException e) {
                say(app, "Could not reach the TV: " + e.getMessage());
            }
        }, "arclink-send").start();
    }

    static void say(Context c, String text) {
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
