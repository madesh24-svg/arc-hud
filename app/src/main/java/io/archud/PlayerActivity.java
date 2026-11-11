package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
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
 * ARC PLAYER: plays a video sent from a phone through ARC LINK. Three ways to get the video:
 * a small file kept in the app's private cache (deleted when this screen closes); a continuous stream
 * from the phone, which repackages the video and starts it at any time asked for (see PhoneStream);
 * or, for files the phone cannot repackage, byte ranges read over the network (see RangeSource).
 * Remote: OK = pause/play, LEFT/RIGHT = fast scan (2x up to 128x, then normal), BACK = close.
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_FILE = "file";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_TOKEN = "token";
    /** "ts" = stream from the phone; absent = byte ranges. */
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_DURATION = "duration";
    static final String EXTRA_BPS = "bps";
    static final String EXTRA_NOTE = "note";

    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    /** Fast-forward / rewind speeds: video seconds per real second; the press after the last returns to normal. */
    private static final int[] SCAN_SPEEDS = {2, 4, 8, 32, 64, 128};
    private static final long SCAN_TICK_MS = 400;
    /** A scan over a phone stream shows a short piece after each jump, so the jumps are further apart. */
    private static final long STREAM_SCAN_TICK_MS = 1200;
    private static final int MAX_RECOVERIES = 5;
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
    private BufferInfo buffer;
    private boolean surfaceReady;
    private boolean prepared;
    private boolean failed;
    private int generation;
    private File file;
    private Intent pending;
    /** Remote video only: true while paused to fill the buffer (first start, or after it ran dry). */
    private boolean waiting;
    private boolean starting;
    private long waitSince;
    private int scanDir;
    private int scanLevel;
    private long scanPosMs;
    private boolean seekPending;
    private long seekAt;
    private String skipNote = "";
    private long skipNoteUntil;
    // stream from the phone
    private boolean tsMode;
    private String tsHost;
    private int tsPort;
    private String tsToken;
    private long totalMs;
    private long bytesPerSec;
    private long streamStartMs;
    private String note = "";
    private boolean quickStart;
    private boolean restarting;
    private int recoveries;
    /** 0 = stream with sound, 1 = stream picture only, then the older byte-range method. */
    private int tsAttempt;
    private boolean playedOnce;
    private String rangePath;

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setVisibility(View.GONE);
        }
    };

    private final Runnable hideMessage = new Runnable() {
        @Override
        public void run() {
            if (!waiting && !failed) {
                message.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (scanDir == 0) {
                return;
            }
            if (tsMode) {
                streamScanStep();
                showOverlay();
                handler.postDelayed(this, STREAM_SCAN_TICK_MS);
                return;
            }
            if (player == null || !prepared) {
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
        PhoneStream.purge(new File(getCacheDir(), "arcbuf"));

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

    private MediaPlayer newPlayer() {
        final MediaPlayer mp = new MediaPlayer();
        mp.setSurface(output);
        mp.setOnPreparedListener(p -> onPrepared(p));
        mp.setOnVideoSizeChangedListener((p, w, h) -> fit(w, h));
        mp.setOnCompletionListener(p -> onCompleted(p));
        mp.setOnSeekCompleteListener(p -> seekPending = false);
        mp.setOnErrorListener((p, what, extra) -> {
            if (p == player) {
                onPlayerError();
            }
            return true;
        });
        return mp;
    }

    private void onPrepared(MediaPlayer p) {
        if (p != player) {
            return;
        }
        prepared = true;
        restarting = false;
        if (buffer == null || quickStart) {
            // a stored file, or a picture shown while scanning: start at once
            waiting = false;
            playedOnce = true;
            p.start();
            showOverlay();
            return;
        }
        // video on the phone: bufferTick starts it once 20-30 s are buffered
        waiting = true;
        starting = true;
        waitSince = SystemClock.elapsedRealtime();
        message.setText("Buffering...");
        message.setVisibility(View.VISIBLE);
    }

    private void onCompleted(MediaPlayer p) {
        if (p != player) {
            return;
        }
        if (tsMode && scanDir == 0 && totalMs > 0 && posMs() < totalMs - 20_000 && recoveries < MAX_RECOVERIES) {
            // the stream ended long before the video did (Wi-Fi or phone hiccup): carry on from here
            recoveries++;
            restartStream(posMs(), true, false);
            return;
        }
        finish();
    }

    private void onPlayerError() {
        if (tsMode && !failed && !playedOnce) {
            // this TV's stream reader refused the stream before anything played: try a simpler way
            tsAttempt++;
            if (tsAttempt == 1) {
                note = "No sound: this TV could not read the sound track";
                restartStream(streamStartMs, false, false);
                return;
            }
            tsMode = false;
            generation++;
            dropMedia();
            startRange(tsHost, tsPort, rangePath, tsToken);
            return;
        }
        if (tsMode && !failed && recoveries < MAX_RECOVERIES) {
            recoveries++;
            restartStream(posMs(), true, false);
            return;
        }
        fail("This TV could not play this video.\nIts format or sound track is not supported.\n\n"
                + "Press BACK to close.");
    }

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
        recoveries = 0;
        tsMode = false;
        note = "";
        message.setVisibility(View.GONE);
        title.setText(intent.getStringExtra(EXTRA_TITLE));
        final int mine = ++generation;

        String path = intent.getStringExtra(EXTRA_FILE);
        if (path != null) {
            final MediaPlayer mp = newPlayer();
            player = mp;
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
        } else if ("ts".equals(intent.getStringExtra(EXTRA_MODE))) {
            file = null;
            currentPath = null;
            tsMode = true;
            tsHost = intent.getStringExtra(EXTRA_HOST);
            tsPort = intent.getIntExtra(EXTRA_PORT, 0);
            tsToken = intent.getStringExtra(EXTRA_TOKEN);
            rangePath = intent.getStringExtra(EXTRA_PATH);
            tsAttempt = 0;
            playedOnce = false;
            totalMs = intent.getLongExtra(EXTRA_DURATION, 0);
            bytesPerSec = intent.getLongExtra(EXTRA_BPS, 0);
            String n = intent.getStringExtra(EXTRA_NOTE);
            note = n == null ? "" : n;
            streamStartMs = 0;
            restartStream(0, true, false);
        } else {
            file = null;
            currentPath = null;
            startRange(intent.getStringExtra(EXTRA_HOST), intent.getIntExtra(EXTRA_PORT, 0),
                    intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_TOKEN));
        }
        if (previous != null && !previous.equals(file)) {
            previous.delete();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** The older method: read the phone's file in byte ranges (see RangeSource). */
    private void startRange(final String host, final int port, final String remotePath, final String token) {
        final int mine = ++generation;
        final MediaPlayer mp = newPlayer();
        player = mp;
        failed = false;
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
                    buffer = source;
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
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /**
     * Phone stream: (re)open it at a time. This is how seeking works: the phone starts a new stream at
     * the requested time. quick = no buffering wait (pictures shown while scanning); audio=false sends
     * the picture only.
     */
    private void restartStream(final long wantMs, final boolean audio, final boolean quick) {
        dropMedia();
        failed = false;
        quickStart = quick;
        restarting = true;
        final int mine = ++generation;
        final MediaPlayer mp = newPlayer();
        player = mp;
        if (!quick) {
            message.setText("Connecting to the phone...");
            message.setVisibility(View.VISIBLE);
        }
        final String host = tsHost;
        final int port = tsPort;
        final String token = tsToken;
        new Thread(() -> {
            try {
                final PhoneStream stream = new PhoneStream(host, port, token, wantMs, audio,
                        new File(getCacheDir(), "arcbuf"));
                runOnUiThread(() -> {
                    if (mine != generation || player != mp) {
                        stream.close();
                        return;
                    }
                    buffer = stream;
                    streamStartMs = stream.startMs;
                    try {
                        mp.setDataSource(stream);
                        mp.prepareAsync();
                    } catch (RuntimeException e) {
                        onPlayerError();
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
        }, "arc-stream-open").start();
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
        BufferInfo src = buffer;
        MediaPlayer mp = player;
        if (src == null || mp == null || !prepared || failed) {
            return;
        }
        long perSec;
        if (tsMode) {
            perSec = bytesPerSec > 0 ? bytesPerSec : 150_000L;
        } else {
            int durationMs = mp.getDuration();
            long size = src.sizeBytes();
            perSec = durationMs > 0 && size > 0 ? Math.max(1, size * 1000L / durationMs) : 600_000L;
        }
        long startBytes = clamp(perSec * 25, tsMode ? 1L << 20 : 4L << 20, tsMode ? 20L << 20 : 28L << 20);
        long resumeBytes = clamp(perSec * 10, tsMode ? 1L << 20 : 2L << 20, 12L << 20);
        long lowBytes = Math.max(tsMode ? 256L << 10 : 512L << 10, perSec * 2);
        long ahead = src.ahead();
        boolean rest = src.restBuffered();
        long now = SystemClock.elapsedRealtime();
        if (waiting) {
            long need = starting ? startBytes : resumeBytes;
            if (ahead >= need || rest || now - waitSince > 30_000) {
                waiting = false;
                starting = false;
                playedOnce = true;
                message.setVisibility(View.GONE);
                mp.start();
                showOverlay();
                if (!note.isEmpty()) {
                    message.setText(note);
                    message.setVisibility(View.VISIBLE);
                    handler.postDelayed(hideMessage, 6000);
                    note = "";
                }
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

    /** Lets go of the player and its buffered data (a scan may carry on and start a new one). */
    private void dropMedia() {
        prepared = false;
        waiting = false;
        seekPending = false;
        if (buffer != null) {
            buffer.close();
            buffer = null;
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

    private void releasePlayer() {
        scanDir = 0;
        handler.removeCallbacks(scanTick);
        skipNote = "";
        skipNoteUntil = 0;
        dropMedia();
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
        if (failed || (!prepared && scanDir == 0)) {
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
                if (scanDir != 0) {
                    stopScan(true);
                    return true;
                }
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
            if (tsMode) {
                scanPosMs = posMs();
                restarting = false;
            } else {
                player.setVolume(0f, 0f);
                if (!player.isPlaying()) {
                    player.start();
                }
                seekPending = false;
            }
            handler.removeCallbacks(scanTick);
            handler.post(scanTick);
        }
        skipNote = (direction > 0 ? ">> " : "<< ") + SCAN_SPEEDS[scanLevel] + "x";
        skipNoteUntil = Long.MAX_VALUE;
        showOverlay();
    }

    /** One jump of a scan over a phone stream: open the stream a little further on (picture only). */
    private void streamScanStep() {
        if (restarting) {
            return;
        }
        long target = scanPosMs + SCAN_SPEEDS[scanLevel] * STREAM_SCAN_TICK_MS * scanDir;
        if (target <= 0) {
            scanPosMs = 0;
            stopScan(true);
            return;
        }
        if (totalMs > 0 && target >= totalMs - 3000) {
            scanPosMs = Math.max(0, totalMs - 4000);
            stopScan(true);
            return;
        }
        scanPosMs = target;
        restartStream(target, false, true);
    }

    /** Ends a scan and carries on playing normally from where the scan stopped. */
    private void stopScan(boolean play) {
        if (scanDir == 0) {
            return;
        }
        scanDir = 0;
        handler.removeCallbacks(scanTick);
        seekPending = false;
        skipNote = "";
        skipNoteUntil = 0;
        if (tsMode) {
            restartStream(scanPosMs, true, false);
            showOverlay();
            return;
        }
        if (player != null) {
            player.setVolume(1f, 1f);
            if (play) {
                player.start();
            }
        }
        showOverlay();
    }

    private void showOverlay() {
        refresh();
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_MS);
    }

    /** Where the video is, in milliseconds from its very start (a phone stream counts from its start point). */
    private int posMs() {
        int p = player != null && prepared ? player.getCurrentPosition() : 0;
        if (tsMode) {
            return (int) Math.min(Integer.MAX_VALUE, streamStartMs + p);
        }
        return p;
    }

    private int durMs() {
        if (tsMode) {
            return (int) Math.min(Integer.MAX_VALUE, totalMs);
        }
        return player != null && prepared ? player.getDuration() : 0;
    }

    private void refresh() {
        if (player == null || !prepared) {
            return;
        }
        int total = durMs();
        int pos = scanDir != 0 && tsMode ? (int) Math.min(Integer.MAX_VALUE, scanPosMs) : posMs();
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
