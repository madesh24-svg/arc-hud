package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

/**
 * ARC AUDIO: plays the songs a phone sent through ARC LINK, one after the other, with the cover, title and
 * artist on screen. Remote: OK = pause/play, LEFT/RIGHT = back/forward 10 s, NEXT/PREVIOUS = song, BACK = close.
 * The phone can do the same through the commands in LinkServer.
 */
public class AudioActivity extends Activity {
    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int SEEK_MS = 10_000;
    /** Pressing PREVIOUS after this long into a song restarts it instead of going back a song. */
    private static final int RESTART_MS = 3000;

    private static AudioActivity live;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean prepared;
    private boolean ended;
    private AudioStore.Track loaded;
    private Bitmap coverBmp;
    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView count;
    private TextView time;
    private TextView hint;
    private ProgressBar bar;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    /** Closes the player if it is open (the phone pressed STOP). */
    static void closeIfOpen() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (live != null) {
                    live.finish();
                }
            }
        });
    }

    /** A command from the phone: toggle, next, prev, or seek to a number of seconds. */
    static void command(final String cmd, final int seconds) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (live != null) {
                    live.apply(cmd, seconds);
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float px = getResources().getDisplayMetrics().widthPixels / 1280f;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF02081A);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.round(340 * px);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF0A2236);
        row.addView(cover, new LinearLayout.LayoutParams(side, side));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        int gap = Math.round(40 * px);
        text.setPadding(gap, 0, 0, 0);
        TextView brand = label(px, 16, CYAN, true);
        brand.setText("ARC AUDIO");
        brand.setLetterSpacing(0.2f);
        title = label(px, 34, WHITE, true);
        title.setMaxLines(2);
        artist = label(px, 22, DIM, false);
        count = label(px, 16, CYAN, false);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        time = label(px, 16, WHITE, false);
        hint = label(px, 14, DIM, false);
        hint.setText("OK pause/play    LEFT / RIGHT 10 s    NEXT / PREVIOUS song    BACK close");
        text.addView(brand);
        text.addView(title);
        text.addView(artist);
        text.addView(count);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(10 * px));
        barLp.topMargin = Math.round(28 * px);
        text.addView(bar, barLp);
        text.addView(time);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Math.round(36 * px);
        text.addView(hint, hintLp);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        int margin = Math.round(90 * px);
        rowLp.setMargins(margin, margin / 2, margin, margin / 2);
        root.addView(row, rowLp);
        setContentView(root);
        live = this;
        AudioStore.setOpen(true);
        playCurrent();
        handler.post(ticker);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        playCurrent();
    }

    @Override
    protected void onDestroy() {
        live = null;
        handler.removeCallbacksAndMessages(null);
        release();
        AudioStore.setOpen(false);
        AudioStore.clear();
        cover.setImageDrawable(null);
        if (coverBmp != null) {
            coverBmp.recycle();
            coverBmp = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                apply("toggle", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null && prepared && !player.isPlaying()) {
                    apply("toggle", 0);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null && prepared && player.isPlaying()) {
                    apply("toggle", 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                apply("next", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                apply("prev", 0);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void apply(String cmd, int seconds) {
        if ("next".equals(cmd)) {
            if (AudioStore.step(1)) {
                playCurrent();
            }
        } else if ("prev".equals(cmd)) {
            if (player != null && prepared && player.getCurrentPosition() > RESTART_MS) {
                player.seekTo(0);
            } else if (AudioStore.step(-1)) {
                playCurrent();
            }
        } else if ("seek".equals(cmd)) {
            if (player != null && prepared) {
                player.seekTo(Math.max(0, seconds) * 1000);
            }
        } else if (player != null && prepared) {
            if (ended) {
                ended = false;
                player.seekTo(0);
                player.start();
            } else if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
        }
        refresh();
    }

    private void seekBy(int ms) {
        if (player == null || !prepared) {
            return;
        }
        int target = player.getCurrentPosition() + ms;
        int length = player.getDuration();
        player.seekTo(Math.max(0, length > 0 ? Math.min(target, length - 500) : target));
        refresh();
    }

    // ---------------------------------------------------------------- playback

    /** Plays the song the store says is current, unless it is already the one playing. */
    private void playCurrent() {
        final AudioStore.Track t = AudioStore.current();
        if (t == null || (t == loaded && prepared)) {
            return;
        }
        release();
        loaded = t;
        ended = false;
        title.setText(t.title);
        artist.setText(t.artist);
        count.setText(AudioStore.pos() + " / " + AudioStore.total());
        showCover(t);
        hint.setText("OK pause/play    LEFT / RIGHT 10 s    NEXT / PREVIOUS song    BACK close");
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer p) {
                if (p == player) {
                    prepared = true;
                    p.start();
                    refresh();
                }
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer p) {
                if (p == player) {
                    onSongEnded();
                }
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer p, int what, int extra) {
                if (p == player) {
                    onSongFailed();
                }
                return true;
            }
        });
        try {
            mp.setDataSource(t.song.getAbsolutePath());
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            onSongFailed();
        }
    }

    private void onSongEnded() {
        if (AudioStore.step(1)) {
            playCurrent();
            return;
        }
        ended = true;
        AudioStore.setPlayback(false, 0, 0);
        hint.setText("End of the queue. Press OK to play again, or BACK to close.");
    }

    private void onSongFailed() {
        prepared = false;
        hint.setText("This TV could not play this song. Trying the next one...");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isDestroyed() && AudioStore.step(1)) {
                    playCurrent();
                }
            }
        }, 1500);
    }

    private void release() {
        prepared = false;
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
                // already released
            }
        }
    }

    // ---------------------------------------------------------------- screen

    private void showCover(AudioStore.Track t) {
        Bitmap b = null;
        if (t.cover != null) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                b = BitmapFactory.decodeFile(t.cover.getAbsolutePath(), o);
            } catch (RuntimeException | OutOfMemoryError e) {
                b = null;
            }
        }
        cover.setImageBitmap(b);
        if (coverBmp != null) {
            coverBmp.recycle();
        }
        coverBmp = b;
    }

    private void refresh() {
        if (ended) {
            // keep telling the phone the queue is finished (length 0) until the song is played again
            AudioStore.setPlayback(false, 0, 0);
            return;
        }
        if (player == null || !prepared) {
            return;
        }
        int pos = player.getCurrentPosition();
        int length = Math.max(1, player.getDuration());
        bar.setProgress((int) (1000L * pos / length));
        time.setText(clock(pos) + " / " + clock(length) + (player.isPlaying() || ended ? "" : "    PAUSED"));
        AudioStore.setPlayback(player.isPlaying(), pos / 1000, length / 1000);
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private TextView label(float px, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * px * 1.25f);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setSingleLine(false);
        return t;
    }
}
