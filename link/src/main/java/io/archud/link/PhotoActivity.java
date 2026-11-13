package io.archud.link;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
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
import java.util.concurrent.RejectedExecutionException;

/**
 * ARC PHOTOS on the phone. Two ways in: the whole phone gallery as a scrolling grid (the PICK PHOTOS button),
 * or the photos chosen in the phone's share sheet. Tap a photo and it goes on the TV and stays there until
 * another is chosen. Each photo is shrunk here to a TV-sized JPEG (long edge 1280 px) and sent to the TV a few
 * at a time (the one shown, the next two and the previous one); the originals never leave the phone. The TV is
 * polled so that LEFT/RIGHT on the TV remote moves the selection here too. Optional slideshow: long-press photos
 * to pick a set; with none picked it plays the whole gallery.
 */
public class PhotoActivity extends JarvisActivity {
    static final int MAX_PHOTOS = 200;
    /** Start this screen with this extra set to true to browse the whole phone gallery. */
    static final String EXTRA_GALLERY = "gallery";
    /** The TV counts photos up to 9999. */
    private static final int GALLERY_MAX = 9000;
    private static final int MAX_EDGE = 1280;
    /** The TV keeps photos this close (around the album) to the one on screen; see ImageStore on the TV. */
    private static final int TV_KEEP = 4;
    private static final long POLL_MS = 1500;
    private static final long[] INTERVALS = {2000, 4000, 8000};
    private static final int REQUEST_GALLERY = 21;

    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;
    private static final int CELL = 0xFF0A2236;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Makes the grid thumbnails and reads the gallery, apart from the worker so sending is never held up. */
    private final ExecutorService thumbMaker = Executors.newFixedThreadPool(2);
    private final LruCache<Integer, Bitmap> thumbCache = new LruCache<Integer, Bitmap>(16 * 1024 * 1024) {
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
    /** The grid rows on screen, so thumbnails for rows that have scrolled away are not made. */
    private volatile int visFirst = 0;
    private volatile int visLast = 40;
    private String host;
    private String pin;
    /** The photo on the TV (album index), or -1 before the first pick. */
    private int pos = -1;
    private boolean galleryMode;
    private boolean slideshow;
    private int intervalIdx = 1;
    private float density;
    /** Photos picked for the slideshow (long-press). With none picked the slideshow plays everything. */
    private boolean[] picked = new boolean[0];
    private int pickedCount;

    private ImageView preview;
    private TextView counter;
    private Hud.Status status;
    private TextView slideButton;
    private TextView intervalButton;
    private GridView grid;
    private BaseAdapter adapter;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (slideshow) {
                go(nextInSlideshow(pos));
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
        if (!Store.paired(this)) {
            Sender.say(this, "Pair with the TV first.");
            finish();
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_GALLERY, false)) {
            galleryMode = true;
            loadGallery();
        } else if (!loadShared(getIntent())) {
            Sender.say(this, "No photos to send.");
            finish();
            return;
        }
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_GALLERY, false)) {
            if (!galleryMode || uris.isEmpty()) {
                galleryMode = true;
                loadGallery();
            }
        } else if (loadShared(intent)) {
            galleryMode = false;
        }
    }

    @Override
    protected void onDestroy() {
        // the TV keeps showing the last photo; STOP CASTING (or BACK on the TV) closes it
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        thumbMaker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- getting the photos

    /** Takes the photos chosen in the share sheet and shows the first. False when the intent holds none. */
    private boolean loadShared(Intent in) {
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
        applyList(list, true);
        return true;
    }

    private String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** Lists every photo on the phone, newest first, once the phone's photo permission is given. */
    private void loadGallery() {
        if (checkSelfPermission(photoPermission()) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow ARC LINK to see your photos to browse the gallery.");
            requestPermissions(new String[] {photoPermission()}, REQUEST_GALLERY);
            return;
        }
        status.setText("Reading the gallery...");
        try {
            thumbMaker.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Uri> list = new ArrayList<>();
                    Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    try (Cursor c = getContentResolver().query(base, new String[] {MediaStore.Images.Media._ID},
                            null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                        if (c != null) {
                            int col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                            while (c.moveToNext() && list.size() < GALLERY_MAX) {
                                list.add(ContentUris.withAppendedId(base, c.getLong(col)));
                            }
                        }
                    } catch (RuntimeException e) {
                        // no access: the list stays empty and the screen says so
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (list.isEmpty()) {
                                status.setText("No photos found. Check that ARC LINK may see photos "
                                        + "(phone settings, Apps, ARC LINK, Permissions).");
                            } else {
                                applyList(list, false);
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            // the screen is closing
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_GALLERY) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadGallery();
            } else {
                status.setText("ARC LINK cannot see your photos. Allow it in the phone's app settings, "
                        + "or share photos to ARC LINK from the gallery instead.");
            }
        }
    }

    /** Starts over with this list. When castFirst, the first photo goes on the TV; otherwise the TV is untouched. */
    private void applyList(List<Uri> list, boolean castFirst) {
        stopSlideshow();
        gen++;
        uris = list;
        picked = new boolean[list.size()];
        pickedCount = 0;
        pos = -1;
        thumbCache.evictAll();
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
        adapter.notifyDataSetChanged();
        updateCounter();
        if (castFirst) {
            go(0);
        } else {
            status.setText(list.size() + " photos. Tap one to show it on the TV.");
            preview.setImageDrawable(null);
        }
    }

    // ---------------------------------------------------------------- moving

    private void go(int target) {
        int n = uris.size();
        if (n == 0) {
            return;
        }
        pos = ((target % n) + n) % n;
        updateCounter();
        updateStrip(slideshow);
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
        for (int d = -keep; d <= keep; d++) {
            s.add(((target + d) % n + n) % n);
        }
        return s;
    }

    // ---------------------------------------------------------------- the TV poll

    /** Asks the TV what it shows, so a remote LEFT/RIGHT on the TV moves the selection here too. */
    private void pollTv() {
        if (polling) {
            return;
        }
        polling = true;
        final int g = gen;
        final String sess = session;
        try {
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
        } catch (RejectedExecutionException e) {
            polling = false;
        }
    }

    private void applyState(int g, String sess, String[] t, int shownThen) {
        if (g != gen || !sess.equals(session)) {
            return;
        }
        if (t.length == 1) {
            if (shownThen >= 0) {
                lastShown = -1;
                stopSlideshow();
                status.setText("The TV closed the viewer. Tap a photo to show it again.");
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
            updateStrip(true);
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

    // ---------------------------------------------------------------- the grid

    /** The next photo for the slideshow: the next picked one, or simply the next when none are picked. */
    private int nextInSlideshow(int from) {
        int n = uris.size();
        if (pickedCount == 0) {
            return from + 1;
        }
        for (int step = 1; step <= n; step++) {
            int i = (from + step) % n;
            if (picked[i]) {
                return i;
            }
        }
        return from;
    }

    private void updateCounter() {
        int n = uris.size();
        String where = pos < 0 ? "PICK A PHOTO" : (pos + 1) + " / " + n;
        counter.setText(where + (pickedCount > 0 ? "   SLIDESHOW: " + pickedCount + " PICKED" : "   SLIDESHOW: ALL"));
    }

    private void togglePick(int i) {
        if (i < 0 || i >= picked.length) {
            return;
        }
        picked[i] = !picked[i];
        pickedCount += picked[i] ? 1 : -1;
        updateCounter();
        updateStrip(false);
    }

    /** Redraws the grid cells (highlight on the photo on the TV, gold on the picked ones). */
    private void updateStrip(boolean reveal) {
        adapter.notifyDataSetChanged();
        if (reveal && pos >= 0 && (pos < grid.getFirstVisiblePosition() || pos > grid.getLastVisiblePosition())) {
            grid.smoothScrollToPosition(pos);
        }
    }

    private void styleCell(ImageView v, int i) {
        v.setPadding(dp(2), dp(2), dp(2), dp(2));
        v.setBackgroundColor(i == pos ? CYAN : (i < picked.length && picked[i] ? GOLD : CELL));
    }

    private ImageView newCell() {
        ImageView v = new ImageView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, widthSpec);
            }
        };
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private void loadThumb(final List<Uri> list, final int i) {
        try {
            thumbMaker.execute(new Runnable() {
                @Override
                public void run() {
                    if (i < visFirst - 12 || i > visLast + 12 || list != uris) {
                        return;
                    }
                    final Bitmap b = makeThumb(list.get(i));
                    if (b == null) {
                        return;
                    }
                    thumbCache.put(i, b);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (list == uris) {
                                showThumb(i, b);
                            }
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            // the screen is closing
        }
    }

    private void showThumb(int i, Bitmap b) {
        for (int c = 0; c < grid.getChildCount(); c++) {
            View cell = grid.getChildAt(c);
            if (cell.getTag() instanceof Integer && (Integer) cell.getTag() == i) {
                ((ImageView) cell).setImageBitmap(b);
            }
        }
    }

    /** A small upright picture of the photo for the grid, or null if unreadable. */
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
            while (big / (sample * 2) >= 200) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
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

    // ---------------------------------------------------------------- buttons

    private void toggleSlideshow() {
        if (slideshow) {
            stopSlideshow();
            return;
        }
        if (uris.isEmpty()) {
            return;
        }
        slideshow = true;
        slideButton.setText("PAUSE");
        if (pos < 0) {
            go(pickedCount > 0 ? nextInSlideshow(-1) : 0);
        }
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
        Hud.bars(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Hud.background(this));
        col.setPadding(dp(16), dp(20), dp(16), dp(16));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        names.addView(Hud.label(this, "PHOTOS", 18, Hud.WHITE, true));
        names.addView(Hud.label(this, "CASTING TO THE TV", 10, Hud.CYAN, false));
        brand.addView(names);
        col.addView(brand);
        col.addView(Hud.rule(this));
        status = new Hud.Status(this);
        col.addView(status);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Hud.box(this, 0xFF000000, Hud.FRAME));
        preview.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                0, 2f);
        previewLp.topMargin = dp(8);
        col.addView(preview, previewLp);
        final GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) {
                    go(vx < 0 ? pos + 1 : (pos < 0 ? 0 : pos - 1));
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

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(3));
        grid.setVerticalSpacing(dp(3));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return uris.size();
            }

            @Override
            public Object getItem(int position) {
                return null;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView v = convertView != null ? (ImageView) convertView : newCell();
                v.setTag(position);
                Bitmap have = thumbCache.get(position);
                v.setImageBitmap(have);
                if (have == null) {
                    loadThumb(uris, position);
                }
                styleCell(v, position);
                return v;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                go(position);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                togglePick(position);
                return true;
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                visFirst = first;
                visLast = first + visible;
            }
        });
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                0, 3f);
        gridLp.topMargin = dp(8);
        col.addView(grid, gridLp);

        counter = Hud.label(this, "", 13, Hud.WHITE, true);
        counter.setGravity(Gravity.CENTER);
        col.addView(counter);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(Hud.button(this, "PREV", Hud.OUTLINE, true, new Runnable() {
            @Override
            public void run() {
                go(pos < 0 ? 0 : pos - 1);
            }
        }));
        slideButton = Hud.button(this, "SLIDESHOW", Hud.SOLID, true, new Runnable() {
            @Override
            public void run() {
                toggleSlideshow();
            }
        });
        row1.addView(slideButton);
        row1.addView(Hud.button(this, "NEXT", Hud.OUTLINE, true, new Runnable() {
            @Override
            public void run() {
                go(pos + 1);
            }
        }));
        col.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        intervalButton = Hud.button(this, "EVERY 4 s", Hud.OUTLINE, true, new Runnable() {
            @Override
            public void run() {
                nextInterval();
            }
        });
        row2.addView(intervalButton);
        row2.addView(Hud.button(this, "STOP CASTING", Hud.DANGER, true, new Runnable() {
            @Override
            public void run() {
                stopCasting();
            }
        }));
        col.addView(row2);
        col.addView(Hud.label(this, "Tap a photo: it goes on the TV and stays until you pick another. "
                + "Long-press photos to pick a set for the slideshow (none picked = all).", 10, Hud.DIM, false));
        setContentView(col);
    }
}
