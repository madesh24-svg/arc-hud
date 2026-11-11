package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Plays a video sent from a phone through ARC LINK. The file lives in the app's private cache and is
 * deleted when this screen closes (BACK), so it never fills the TV's small storage.
 * Remote: OK = pause/play, LEFT/RIGHT = 10 s, UP/DOWN = 1 min, BACK = close.
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_FILE = "file";
    static final String EXTRA_TITLE = "title";

    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int SHORT_SEEK_MS = 10_000;
    private static final int LONG_SEEK_MS = 60_000;
    private static final long OVERLAY_MS = 4000;

    private static volatile String currentPath;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private LinearLayout overlay;
    private ProgressBar bar;
    private TextView title;
    private TextView time;
    private TextView message;
    private File file;
    private boolean failed;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    /** Absolute path of the video on screen, or null. LinkServer keeps this file when cleaning up. */
    static String current() {
        return currentPath;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float px = getResources().getDisplayMetrics().widthPixels / 1280f;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0xB0000000);
        int pad = Math.round(24 * px);
        overlay.setPadding(pad * 2, pad, pad * 2, pad);
        title = label(px, 18, WHITE);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        time = label(px, 16, CYAN);
        overlay.addView(title);
        overlay.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(14 * px)));
        overlay.addView(time);
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        message = label(px, 22, WHITE);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        setContentView(root);

        video.setOnPreparedListener(mp -> {
            video.start();
            showOverlay();
        });
        video.setOnCompletionListener(mp -> finish());
        video.setOnErrorListener((mp, what, extra) -> {
            fail("This TV could not play this video.\nIts format or sound track is not supported.\n\n"
                    + "Press BACK to close.");
            return true;
        });
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        load(intent);
    }

    /** Starts the file named in the intent and removes the one played before. */
    private void load(Intent intent) {
        File previous = file;
        String path = intent.getStringExtra(EXTRA_FILE);
        File next = path == null ? null : new File(path);
        if (next == null || !insideCache(next) || !next.isFile()) {
            fail("Nothing to play.\n\nPress BACK to close.");
            return;
        }
        file = next;
        currentPath = next.getAbsolutePath();
        failed = false;
        message.setVisibility(View.GONE);
        title.setText(intent.getStringExtra(EXTRA_TITLE));
        video.setVideoPath(next.getAbsolutePath());
        if (previous != null && !previous.equals(next)) {
            previous.delete();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private boolean insideCache(File f) {
        try {
            return f.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath() + File.separator)
                    && f.getName().startsWith(LinkServer.VIDEO_PREFIX);
        } catch (IOException e) {
            return false;
        }
    }

    private void fail(String text) {
        failed = true;
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (failed) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) {
                    video.pause();
                } else {
                    video.start();
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                video.start();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                video.pause();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(SHORT_SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-SHORT_SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                seek(LONG_SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                seek(-LONG_SEEK_MS);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void seek(int deltaMs) {
        int total = video.getDuration();
        int target = Math.max(0, video.getCurrentPosition() + deltaMs);
        if (total > 0) {
            target = Math.min(target, total - 500);
        }
        video.seekTo(Math.max(0, target));
        showOverlay();
    }

    private void showOverlay() {
        refresh();
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_MS);
    }

    private void refresh() {
        int total = video.getDuration();
        int pos = video.getCurrentPosition();
        bar.setProgress(total > 0 ? (int) (1000L * pos / total) : 0);
        time.setText(clock(pos) + " / " + (total > 0 ? clock(total) : "--:--")
                + (video.isPlaying() ? "" : "   PAUSED"));
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private TextView label(float px, float size, int color) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * px * 1.25f);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        if (file != null) {
            file.delete();
        }
        currentPath = null;
        super.onDestroy();
    }
}
