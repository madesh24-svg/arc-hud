package io.archud.link;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ARC PHOTOS on the phone: a pager over the shared photos. The photo on this screen is the photo on the TV.
 * Each photo is shrunk here to a TV-sized JPEG (long edge 1280 px) and sent to the TV a few at a time
 * (the one shown, the next two and the previous one), so the next swipe is instant. The originals never
 * leave the phone. The TV is polled so that LEFT/RIGHT on the TV remote moves this pager too.
 */
public class PhotoActivity extends Activity {
    static final int MAX_PHOTOS = 200;
    private static final int MAX_EDGE = 1280;
    /** The TV keeps photos this close (around the album) to the one on screen; see ImageStore on the TV. */
    private static final int TV_KEEP = 4;
    private static final long POLL_MS = 1500;
    private static final long[] INTERVALS = {2000, 4000, 8000};

    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Makes the small strip thumbnails, apart from the worker so sending to the TV is never held up. */
    private final ExecutorService thumbMaker = Executors.newFixedThreadPool(2);
    private final LruCache<Integer, Bitmap> thumbCache = new LruCache<Integer, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** TV-sized JPEGs already made, by album index. Guarded by itself. */
    private final Map<Integer, byte[]> cache = new HashMap<>();
    /** Album indexes the TV is known to hold. Touched on the worker thread only. */
    private final Set<Integer> sent = new HashSet<>();

    private volatile List<Uri> uris = new ArrayList<>();
    private volatile String session = "";
    private volatile int gen;
    private volatile boolean polling;
    /** The album index last confirmed on the TV screen, or -1. Written on the worker thread. */
    private volatile int lastShown = -1;
    private String host;
    private String pin;
    private int pos;
    private boolean slideshow;
    private int intervalIdx = 1;
    private float density;

    private ImageView preview;
    private TextView counter;
    private TextView status;
    private Button slideButton;
    private Button intervalButton;
    private HorizontalScrollView strip;
    private LinearLayout stripRow;
    private ImageView[] thumbViews = new ImageView[0];
    /** Photos left out of the slideshow (long-press a thumbnail). They can still be shown by tapping them. */
    private boolean[] skipped = new boolean[0];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (slideshow) {
                go(nextIncluded(pos));
                handler.postDelayed(this, INTERVALS[intervalIdx]);
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollTv();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        density = getResources().getDisplayMetrics().density;
        host = Store.host(this);
        pin = Store.pin(this);
        buildScreen();
        if (!Store.paired(this) || !load(getIntent())) {
            Sender.say(this, "No photos to send, or the TV is not paired yet.");
            finish();
            return;
        }
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        load(intent);
    }

    @Override
    protected void onDestroy() {
        // the TV keeps showing the last photo; STOP CASTING (or BACK on the TV) closes it
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        thumbMaker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- loading and moving

    /** Takes the photos from the intent and shows the first. False when the intent holds none. */
    private boolean load(Intent in) {
        List<Uri> list = new ArrayList<>();
        ClipData clip = in.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && list.size() < MAX_PHOTOS; i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) {
                    list.add(u);
                }
            }
        }
        if (list.isEmpty()) {
            return false;
        }
        stopSlideshow();
        uris = list;
        skipped = new boolean[list.size()];
        thumbCache.evictAll();
        rebuildStrip();
        session = String.format(Locale.US, "ph%08x", new SecureRandom().nextInt());
        lastShown = -1;
        synchronized (cache) {
            cache.clear();
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                sent.clear();
            }
        });
        go(0);
        return true;
    }

    private void go(int target) {
        int n = uris.size();
        pos = ((target % n) + n) % n;
        updateCounter();
        updateStrip();
        status.setText("Sending to the TV...");
        cast(pos, ++gen, true);
    }

    /** Makes the TV hold (and, when showIt, show) this photo plus its neighbours. Runs on the worker thread. */
    private void cast(final int target, final int myGen, final boolean showIt) {
        final List<Uri> list = uris;
        final String sess = session;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (myGen != gen) {
                    return;
                }
                try {
                    int n = list.size();
                    synchronized (cache) {
                        cache.keySet().retainAll(near(target, n, 3));
                    }
                    sent.retainAll(near(target, n, TV_KEEP));
                    byte[] data = prepared(list, target);
                    if (data == null) {
                        setStatus(myGen, "Could not read that photo.");
                        return;
                    }
                    showPreview(data, myGen);
                    ensureSent(list, sess, target);
                    if (myGen != gen) {
                        return;
                    }
                    if (showIt) {
                        showOnTv(list, sess, target);
                    }
                    setStatus(myGen, "On the TV");
                    for (int k : window(target, n)) {
                        if (myGen != gen) {
                            return;
                        }
                        ensureSent(list, sess, k);
                    }
                } catch (IOException e) {
                    setStatus(myGen, "Could not reach the TV: " + e.getMessage());
                }
            }
        });
    }

    private void showOnTv(List<Uri> list, String sess, int idx) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            TvClient.Reply r = TvClient.send(host, "/imageshow", pin, headers(sess, idx, list.size()), new byte[0]);
            if (r.ok()) {
                lastShown = idx;
                return;
            }
            if (r.code != 409) {
                throw new IOException(describe(r));
            }
            // the TV viewer was closed since the last photo: it no longer holds any of them
            sent.clear();
            ensureSent(list, sess, idx);
        }
        throw new IOException("the TV would not show the photo");
    }

    private void ensureSent(List<Uri> list, String sess, int idx) throws IOException {
        if (sent.contains(idx)) {
            return;
        }
        byte[] data = prepared(list, idx);
        if (data == null) {
            throw new IOException("a photo could not be read");
        }
        TvClient.Reply r = TvClient.send(host, "/imageput", pin, headers(sess, idx, list.size()), data);
        if (!r.ok()) {
            throw new IOException(describe(r));
        }
        sent.add(idx);
    }

    private static String[] headers(String sess, int idx, int n) {
        return new String[] {"X-Session: " + sess, "X-Pos: " + (idx + 1), "X-Total: " + n};
    }

    private static String describe(TvClient.Reply r) {
        return r.text.isEmpty() ? "the TV answered " + r.code : r.text;
    }

    /** The photos to have ready beside the one shown: the next two and the previous one. */
    private static Set<Integer> window(int target, int n) {
        Set<Integer> w = new LinkedHashSet<>();
        w.add((target + 1) % n);
        w.add((target + 2) % n);
        w.add((target + n - 1) % n);
        w.remove(target);
        return w;
    }

    /** Album indexes within this many places of target, counting around the end of the album. */
    private static Set<Integer> near(int target, int n, int keep) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int d = Math.abs(i - target);
            if (Math.min(d, n - d) <= keep) {
                s.add(i);
            }
        }
        return s;
    }

    // ---------------------------------------------------------------- the TV poll

    /** Asks the TV what it shows, so a remote LEFT/RIGHT on the TV moves this pager too. */
    private void pollTv() {
        if (polling) {
            return;
        }
        polling = true;
        final int g = gen;
        final String sess = session;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    TvClient.Reply r = TvClient.send(host, "/imagestate", pin, new String[0], new byte[0]);
                    if (!r.ok()) {
                        return;
                    }
                    final String[] t = r.text.split(" ");
                    final int shownThen = lastShown;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyState(g, sess, t, shownThen);
                        }
                    });
                } catch (IOException e) {
                    // the TV is busy or asleep; ask again next time
                } finally {
                    polling = false;
                }
            }
        });
    }

    private void applyState(int g, String sess, String[] t, int shownThen) {
        if (g != gen || !sess.equals(session)) {
            return;
        }
        if (t.length == 1) {
            if (shownThen >= 0) {
                lastShown = -1;
                stopSlideshow();
                status.setText("The TV closed the viewer. Swipe to show the photos again.");
            }
            return;
        }
        if (t.length != 3 || !t[0].equals(sess)) {
            return;
        }
        int tvPos;
        try {
            tvPos = Integer.parseInt(t[1]) - 1;
        } catch (NumberFormatException e) {
            return;
        }
        if (tvPos >= 0 && tvPos < uris.size() && tvPos != pos && tvPos != shownThen) {
            // the TV remote moved to another photo: follow it
            stopSlideshow();
            pos = tvPos;
            lastShown = tvPos;
            updateCounter();
            updateStrip();
            cast(pos, ++gen, false);
        }
    }

    // ---------------------------------------------------------------- shrinking photos

    private byte[] prepared(List<Uri> list, int idx) {
        synchronized (cache) {
            byte[] have = cache.get(idx);
            if (have != null) {
                return have;
            }
        }
        byte[] made = shrink(list.get(idx));
        if (made != null) {
            synchronized (cache) {
                cache.put(idx, made);
            }
        }
        return made;
    }

    /** A TV-sized JPEG of the photo (long edge at most 1280 px, rotated upright), or null if unreadable. */
    private byte[] shrink(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (big / (sample * 2) >= MAX_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) {
                return null;
            }
            float scale = Math.min(1f, (float) MAX_EDGE / Math.max(bmp.getWidth(), bmp.getHeight()));
            Matrix m = new Matrix();
            if (scale < 1f) {
                m.postScale(scale, scale);
            }
            int degrees = rotation(uri);
            if (degrees != 0) {
                m.postRotate(degrees);
            }
            Bitmap out = bmp;
            if (!m.isIdentity()) {
                out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (out != bmp) {
                    bmp.recycle();
                }
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 85, buf);
            out.recycle();
            return buf.toByteArray();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private int rotation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return 0;
            }
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) {
                return 90;
            }
            if (o == ExifInterface.ORIENTATION_ROTATE_180) {
                return 180;
            }
            if (o == ExifInterface.ORIENTATION_ROTATE_270) {
                return 270;
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private void showPreview(byte[] data, final int myGen) {
        final Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (myGen == gen && b != null) {
                    preview.setImageBitmap(b);
                }
            }
        });
    }

    private void setStatus(final int myGen, final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (myGen == gen) {
                    status.setText(text);
                }
            }
        });
    }

    // ---------------------------------------------------------------- buttons

    /** The next photo after from that is not left out of the slideshow (from itself if all others are). */
    private int nextIncluded(int from) {
        int n = uris.size();
        for (int step = 1; step <= n; step++) {
            int i = (from + step) % n;
            if (!skipped[i]) {
                return i;
            }
        }
        return from;
    }

    private int includedCount() {
        int c = 0;
        for (boolean s : skipped) {
            if (!s) {
                c++;
            }
        }
        return c;
    }

    private void updateCounter() {
        int n = uris.size();
        int inc = includedCount();
        counter.setText((pos + 1) + " / " + n + (inc < n ? "   SLIDESHOW: " + inc + " PHOTOS" : ""));
    }

    private void toggleSkip(int i) {
        if (i < 0 || i >= skipped.length) {
            return;
        }
        skipped[i] = !skipped[i];
        updateCounter();
        updateStrip();
    }

    /** One thumbnail per photo in a scrolling strip: tap = show on the TV, long-press = in/out of slideshow. */
    private void rebuildStrip() {
        stripRow.removeAllViews();
        final List<Uri> list = uris;
        thumbViews = new ImageView[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setPadding(dp(2), dp(2), dp(2), dp(2));
            v.setBackgroundColor(0xFF0A2236);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    go(index);
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    toggleSkip(index);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(68));
            lp.setMargins(dp(2), 0, dp(2), 0);
            stripRow.addView(v, lp);
            thumbViews[i] = v;
            loadThumb(list, i);
        }
    }

    private void loadThumb(final List<Uri> list, final int i) {
        final Bitmap have = thumbCache.get(i);
        if (have != null) {
            thumbViews[i].setImageBitmap(have);
            return;
        }
        try {
            thumbMaker.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = makeThumb(list.get(i));
                    if (b == null) {
                        return;
                    }
                    thumbCache.put(i, b);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (list == uris && i < thumbViews.length) {
                                thumbViews[i].setImageBitmap(b);
                            }
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // the screen is closing
        }
    }

    /** A small upright square-ish picture of the photo for the strip, or null if unreadable. */
    private Bitmap makeThumb(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            if (big <= 0) {
                return null;
            }
            int sample = 1;
            while (big / (sample * 2) >= 160) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            int degrees = rotation(uri);
            if (bmp == null || degrees == 0) {
                return bmp;
            }
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (turned != bmp) {
                bmp.recycle();
            }
            return turned;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Highlights the photo on the TV, dims the ones left out of the slideshow, scrolls the strip to it. */
    private void updateStrip() {
        for (int i = 0; i < thumbViews.length; i++) {
            thumbViews[i].setBackgroundColor(i == pos ? CYAN : 0xFF0A2236);
            thumbViews[i].setAlpha(i < skipped.length && skipped[i] ? 0.3f : 1f);
        }
        if (pos < thumbViews.length) {
            final View selected = thumbViews[pos];
            strip.post(new Runnable() {
                @Override
                public void run() {
                    strip.smoothScrollTo(selected.getLeft() - (strip.getWidth() - selected.getWidth()) / 2, 0);
                }
            });
        }
    }

    private void toggleSlideshow() {
        if (slideshow) {
            stopSlideshow();
            return;
        }
        if (includedCount() == 0) {
            status.setText("Every photo is left out of the slideshow. Long-press a thumbnail to put it back.");
            return;
        }
        slideshow = true;
        slideButton.setText("PAUSE");
        handler.postDelayed(tick, INTERVALS[intervalIdx]);
    }

    private void stopSlideshow() {
        slideshow = false;
        handler.removeCallbacks(tick);
        if (slideButton != null) {
            slideButton.setText("SLIDESHOW");
        }
    }

    private void nextInterval() {
        intervalIdx = (intervalIdx + 1) % INTERVALS.length;
        intervalButton.setText("EVERY " + (INTERVALS[intervalIdx] / 1000) + " s");
        if (slideshow) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, INTERVALS[intervalIdx]);
        }
    }

    private void stopCasting() {
        stopSlideshow();
        final String h = host;
        final String p = pin;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TvClient.send(h, "/imagestop", p, new String[0], new byte[0]);
                } catch (IOException ignored) {
                    // the TV is already gone
                }
            }
        }, "arclink-photos-stop").start();
        finish();
    }

    // ---------------------------------------------------------------- views

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void buildScreen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(INK);
        col.setPadding(dp(16), dp(24), dp(16), dp(16));
        col.addView(text("ARC LINK  PHOTOS", 18, WHITE, true));
        status = text("", 13, CYAN, false);
        col.addView(status);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF000000);
        col.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) {
                    go(pos + (vx < 0 ? 1 : -1));
                    return true;
                }
                return false;
            }
        });
        preview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return swipe.onTouchEvent(e);
            }
        });

        strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        stripRow = new LinearLayout(this);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(stripRow);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stripLp.topMargin = dp(8);
        col.addView(strip, stripLp);

        counter = text("", 16, WHITE, true);
        counter.setGravity(Gravity.CENTER);
        col.addView(counter);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(button("PREV", new Runnable() {
            @Override
            public void run() {
                go(pos - 1);
            }
        }));
        slideButton = button("SLIDESHOW", new Runnable() {
            @Override
            public void run() {
                toggleSlideshow();
            }
        });
        row1.addView(slideButton);
        row1.addView(button("NEXT", new Runnable() {
            @Override
            public void run() {
                go(pos + 1);
            }
        }));
        col.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        intervalButton = button("EVERY 4 s", new Runnable() {
            @Override
            public void run() {
                nextInterval();
            }
        });
        row2.addView(intervalButton);
        row2.addView(button("STOP CASTING", new Runnable() {
            @Override
            public void run() {
                stopCasting();
            }
        }));
        col.addView(row2);
        col.addView(text("Tap a thumbnail to show it on the TV. Long-press one to leave it out of (or put it back "
                + "into) the slideshow. LEFT/RIGHT on the TV remote work too. "
                + "Leaving this screen keeps the last photo on the TV.", 12, DIM, false));
        setContentView(col);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(INK);
        b.setBackgroundColor(CYAN);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(8), dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }
}
