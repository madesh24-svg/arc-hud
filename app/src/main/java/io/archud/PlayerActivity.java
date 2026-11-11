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
    private static final int SHORT_SEEK_MS = 10_000;
    private static final int LONG_SEEK_MS = 60_000;
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
            p.start();
            showOverlay();
        });
        mp.setOnVideoSizeChangedListener((p, w, h) -> fit(w, h));
        mp.setOnCompletionListener(p -> finish());
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
                    final RangeSource source = new RangeSource(host, port, remotePath, token);
                    runOnUiThread(() -> {
                        if (mine != generation || player != mp) {
                            source.close();
                            return;
                        }
                        message.setVisibility(View.GONE);
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

    private void releasePlayer() {
        prepared = false;
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
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.start();
                }
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.start();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
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
                + (player.isPlaying() ? "" : "   PAUSED"));
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
