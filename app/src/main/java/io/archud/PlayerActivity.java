package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * ARC PLAYER: plays a video sent from a phone through ARC LINK, either a small file kept in the app's
 * private cache (deleted when this screen closes) or a video that stays on the phone and is read over
 * the home network (see RangeSource). Remote: OK = pause/play, LEFT/RIGHT = 10 s, UP/DOWN = 1 min,
 * BACK = close.
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_FILE = "file";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_TOKEN = "token";

    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    /** Fast-forward / rewind speeds: video seconds per real second; the press after the last returns to normal. */
    private static final int[] SCAN_SPEEDS = {2, 4, 8, 32, 64, 128};
    private static final long SCAN_TICK_MS = 400;
    private static final long OVERLAY_MS = 4000;

    private static volatile String currentPath;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView surface;
    private Surface output;
    private LinearLayout overlay;
    private ProgressBar bar;
    private TextView title;
    private TextView time;
    private TextView message;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean prepared;
    private boolean failed;
    private int generation;
    private File file;
    private Intent pending;
    private RangeSource rangeSource;
    /** Remote video only: true while paused to fill the buffer (first start, or after it ran dry). */
    private boolean waiting;
    private boolean starting;
    private long waitSince;
    private int scanDir;
    private int scanLevel;
    private boolean seekPending;
    private long seekAt;
    private String skipNote = "";
    private long skipNoteUntil;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
        }
    };

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (scanDir == 0 || player == null || !prepared) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!seekPending || now - seekAt > 3000) {
                int total = player.getDuration();
                int target = player.getCurrentPosition() + (int) (SCAN_SPEEDS[scanLevel] * SCAN_TICK_MS) * scanDir;
                if (target <= 0) {
                    stopScan(true);
                    player.seekTo(0);
                    return;
                }
                if (total > 0 && target >= total - 1000) {
                    stopScan(true);
                    return;
                }
                seekPending = true;
                seekAt = now;
                player.seekTo(target);
            }
            showOverlay();
            handler.postDelayed(this, SCAN_TICK_MS);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            bufferTick();
            handler.postDelayed(this, 500);
        }
    };

    /** Absolute path of the stored video on screen, or null. LinkServer keeps this file when cleaning up. */
    static String current() {
        return currentPath;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float px = getResources().getDisplayMetrics().widthPixels / 1280f;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // A TextureView, not a SurfaceView: it applies the clip's rotation (phone videos are often
        // portrait) and avoids this TV's hardware video overlay, which ignores rotation and can freeze.
        surface = new TextureView(this);
        surface.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                output = new Surface(st);
                surfaceReady = true;
                if (player != null) {
                    player.setSurface(output);
                }
                startPending();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                // the size follows the video's own size (see fit)
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                if (player != null) {
                    player.setSurface(null);
                }
                if (output != null) {
                    output.release();
                    output = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // each new video frame: nothing to do
            }
        });
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
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
        pending = getIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pending = intent;
        startPending();
    }

    // ---------------------------------------------------------------- playback

    /** Starts the video named in the pending intent, replacing the one before. */
    private void startPending() {
        final Intent intent = pending;
        if (intent == null || !surfaceReady) {
            return;
        }
        pending = null;
        File previous = file;
        releasePlayer();
        failed = false;
        message.setVisibility(View.GONE);
        title.setText(intent.getStringExtra(EXTRA_TITLE));
        final int mine = ++generation;

        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setSurface(output);
        mp.setOnPreparedListener(p -> {
            prepared = true;
            if (rangeSource == null) {
                p.start();
                showOverlay();
            } else {
                // video on the phone: bufferTick starts it once 20-30 s are buffered
                waiting = true;
                starting = true;
                waitSince = SystemClock.elapsedRealtime();
                message.setText("Buffering...");
                message.setVisibility(View.VISIBLE);
            }
        });
        mp.setOnVideoSizeChangedListener((p, w, h) -> fit(w, h));
        mp.setOnCompletionListener(p -> finish());
        mp.setOnSeekCompleteListener(p -> seekPending = false);
        mp.setOnErrorListener((p, what, extra) -> {
            fail("This TV could not play this video.\nIts format or sound track is not supported.\n\n"
                    + "Press BACK to close.");
            return true;
        });

        String path = intent.getStringExtra(EXTRA_FILE);
        if (path != null) {
            File next = new File(path);
            if (!insideCache(next) || !next.isFile()) {
                fail("Nothing to play.\n\nPress BACK to close.");
                return;
            }
            file = next;
            currentPath = next.getAbsolutePath();
            try {
                mp.setDataSource(next.getAbsolutePath());
                mp.prepareAsync();
            } catch (IOException | RuntimeException e) {
                fail("Could not open this video.\n\nPress BACK to close.");
            }
        } else {
            file = null;
            currentPath = null;
            final String host = intent.getStringExtra(EXTRA_HOST);
            final int port = intent.getIntExtra(EXTRA_PORT, 0);
            final String remotePath = intent.getStringExtra(EXTRA_PATH);
            final String token = intent.getStringExtra(EXTRA_TOKEN);
            message.setText("Connecting to the phone...");
            message.setVisibility(View.VISIBLE);
            new Thread(() -> {
                try {
                    final RangeSource source = new RangeSource(host, port, remotePath, token,
                            new File(getCacheDir(), "arcbuf"));
                    runOnUiThread(() -> {
                        if (mine != generation || player != mp) {
                            source.close();
                            return;
                        }
                        message.setVisibility(View.GONE);
                        rangeSource = source;
                        try {
                            mp.setDataSource(source);
                            mp.prepareAsync();
                        } catch (RuntimeException e) {
                            fail("Could not open this video.\n\nPress BACK to close.");
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    runOnUiThread(() -> {
                        if (mine == generation) {
                            fail("Could not reach the phone.\nKeep the ARC app open on the phone.\n\n"
                                    + "Press BACK to close.");
                        }
                    });
                }
            }, "arc-player-open").start();
        }
        if (previous != null && !previous.equals(file)) {
            previous.delete();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** Start or continue playing now (the user asked), ending any buffering wait. */
    private void resume() {
        waiting = false;
        starting = false;
        message.setVisibility(View.GONE);
        player.start();
    }

    /** Remote video: begin once 20-30 s are buffered, pause when the buffer runs dry, resume at about 10 s. */
    private void bufferTick() {
        RangeSource src = rangeSource;
        MediaPlayer mp = player;
        if (src == null || mp == null || !prepared || failed) {
            return;
        }
        int durationMs = mp.getDuration();
        long size = src.getSize();
        long perSec = durationMs > 0 && size > 0 ? Math.max(1, size * 1000L / durationMs) : 600_000L;
        long startBytes = clamp(perSec * 25, 4L << 20, 28L << 20);
        long resumeBytes = clamp(perSec * 10, 2L << 20, 12L << 20);
        long lowBytes = Math.max(512L << 10, perSec * 2);
        long ahead = src.ahead();
        boolean rest = src.restBuffered();
        long now = SystemClock.elapsedRealtime();
        if (waiting) {
            long need = starting ? startBytes : resumeBytes;
            if (ahead >= need || rest || now - waitSince > 30_000) {
                waiting = false;
                starting = false;
                message.setVisibility(View.GONE);
                mp.start();
                showOverlay();
            } else {
                message.setText("Buffering...  " + Math.min(99, 100 * ahead / need) + "%\nWi-Fi "
                        + mbps(src.rate()) + " MB/s, this video needs " + mbps(perSec) + " MB/s");
                message.setVisibility(View.VISIBLE);
            }
        } else if (scanDir == 0 && mp.isPlaying() && ahead < lowBytes && !rest) {
            mp.pause();
            waiting = true;
            waitSince = now;
        }
    }

    private static String mbps(long bytesPerSecond) {
        return String.format(Locale.US, "%.1f", bytesPerSecond / 1048576.0);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void releasePlayer() {
        prepared = false;
        scanDir = 0;
        handler.removeCallbacks(scanTick);
        waiting = false;
        if (rangeSource != null) {
            rangeSource.close();
            rangeSource = null;
        }
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
                // already released
            }
            player = null;
        }
    }

    private boolean insideCache(File f) {
        try {
            return f.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath() + File.separator)
                    && f.getName().startsWith(LinkServer.VIDEO_PREFIX);
        } catch (IOException e) {
            return false;
        }
    }

    private void fit(int w, int h) {
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) {
            return;
        }
        float scale = Math.min((float) rw / w, (float) rh / h);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * scale),
                Math.round(h * scale), Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    private void fail(String text) {
        failed = true;
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (failed || !prepared) {
            return super.onKeyDown(keyCode, event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (scanDir != 0) {
                    stopScan(true);
                    return true;
                }
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    resume();
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (scanDir != 0) {
                    stopScan(true);
                    return true;
                }
                resume();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                stopScan(false);
                player.pause();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                scanKey(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                scanKey(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // no seeking here: on the phone remote the Power key sits right above UP
                showOverlay();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * LEFT/RIGHT work like a classic remote: the first press starts scanning that way at 2x, each further
     * press in the same direction is faster (up to 128x), the press after that returns to normal speed,
     * and OK/Play stops the scan wherever it is. The sound is muted while scanning.
     */
    private void scanKey(int direction) {
        if (scanDir == direction) {
            if (scanLevel + 1 >= SCAN_SPEEDS.length) {
                stopScan(true);
                return;
            }
            scanLevel++;
        } else {
            scanDir = direction;
            scanLevel = 0;
            waiting = false;
            starting = false;
            message.setVisibility(View.GONE);
            player.setVolume(0f, 0f);
            if (!player.isPlaying()) {
                player.start();
            }
            seekPending = false;
            handler.removeCallbacks(scanTick);
            handler.post(scanTick);
        }
        skipNote = (direction > 0 ? ">> " : "<< ") + SCAN_SPEEDS[scanLevel] + "x";
        skipNoteUntil = Long.MAX_VALUE;
        showOverlay();
    }

    /** Ends a scan; with play=true normal playback continues from where the scan stopped. */
    private void stopScan(boolean play) {
        if (scanDir == 0) {
            return;
        }
        scanDir = 0;
        handler.removeCallbacks(scanTick);
        seekPending = false;
        skipNote = "";
        skipNoteUntil = 0;
        if (player != null) {
            player.setVolume(1f, 1f);
            if (play) {
                player.start();
            }
        }
        showOverlay();
    }

    private void seek(int deltaMs) {
        int total = player.getDuration();
        int target = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (total > 0) {
            target = Math.min(target, total - 500);
        }
        player.seekTo(Math.max(0, target));
        showOverlay();
    }

    private void showOverlay() {
        refresh();
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_MS);
    }

    private void refresh() {
        if (player == null || !prepared) {
            return;
        }
        int total = player.getDuration();
        int pos = player.getCurrentPosition();
        bar.setProgress(total > 0 ? (int) (1000L * pos / total) : 0);
        time.setText(clock(pos) + " / " + (total > 0 ? clock(total) : "--:--")
                + (player.isPlaying() ? "" : "   PAUSED")
                + (SystemClock.elapsedRealtime() < skipNoteUntil ? "   " + skipNote : ""));
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
        generation++;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (file != null) {
            file.delete();
        }
        currentPath = null;
        super.onDestroy();
    }
}
