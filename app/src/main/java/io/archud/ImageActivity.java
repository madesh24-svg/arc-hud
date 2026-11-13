package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * ARC PHOTOS: shows the photo a phone sent through ARC LINK and fades to the next one when the phone
 * changes it. Remote: LEFT/RIGHT = previous/next photo already on the TV, BACK = close. The photos
 * are small TV-sized copies; the phone keeps the originals.
 */
public class ImageActivity extends Activity {
    private static final long FADE_MS = 400;
    private static final long LABEL_MS = 3000;
    /** The phone sends photos no larger than this on the long edge; anything bigger is decoded smaller. */
    private static final int MAX_EDGE = 1280;

    private static ImageActivity live;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private ImageView base;
    private ImageView top;
    private TextView label;
    private Bitmap baseBmp;
    private Bitmap pendingBmp;
    private int generation;
    private int shownPos = -1;
    private String shownSession = "";

    private final Runnable hideLabel = new Runnable() {
        @Override
        public void run() {
            label.setVisibility(View.GONE);
        }
    };

    /** Closes the viewer if it is open (the phone pressed STOP). */
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        base = picture();
        top = picture();
        top.setAlpha(0f);
        root.addView(base);
        root.addView(top);
        label = new TextView(this);
        label.setTextColor(0xFFEBFAFF);
        label.setBackgroundColor(0xAA02081A);
        label.setTextSize(22);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setPadding(28, 12, 28, 12);
        label.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 40, 30);
        root.addView(label, lp);
        setContentView(root);
        live = this;
        ImageStore.setOpen(true);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render();
    }

    @Override
    protected void onDestroy() {
        live = null;
        ImageStore.setOpen(false);
        ImageStore.clear();
        handler.removeCallbacksAndMessages(null);
        decoder.shutdownNow();
        base.setImageDrawable(null);
        top.setImageDrawable(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            move(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            move(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            flashLabel();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void move(int delta) {
        if (ImageStore.step(delta)) {
            render();
        } else {
            flashLabel();
        }
    }

    private ImageView picture() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private void flashLabel() {
        label.setText(ImageStore.pos() + " / " + ImageStore.total());
        label.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideLabel);
        handler.postDelayed(hideLabel, LABEL_MS);
    }

    /** Shows the photo the store says is current, decoding it off the main thread. */
    private void render() {
        final int pos = ImageStore.pos();
        final String session = ImageStore.session();
        if (pos == shownPos && session.equals(shownSession)) {
            flashLabel();
            return;
        }
        final File f = ImageStore.currentFile();
        if (f == null) {
            return;
        }
        final int gen = ++generation;
        try {
            decoder.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = decode(f);
                    if (b == null) {
                        return;
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != generation || isDestroyed()) {
                                b.recycle();
                                return;
                            }
                            swap(b);
                            shownPos = pos;
                            shownSession = session;
                            flashLabel();
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            // the screen is closing
        }
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int big = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (big / (sample * 2) >= MAX_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Fades the new photo in over the current one. */
    private void swap(Bitmap b) {
        finishFade();
        pendingBmp = b;
        top.setImageBitmap(b);
        top.setAlpha(0f);
        top.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                finishFade();
            }
        });
    }

    /** Settles a fade: the new photo becomes the base picture and the old bitmap is freed. */
    private void finishFade() {
        top.animate().cancel();
        if (pendingBmp == null) {
            return;
        }
        Bitmap old = baseBmp;
        base.setImageBitmap(pendingBmp);
        baseBmp = pendingBmp;
        pendingBmp = null;
        top.setImageDrawable(null);
        top.setAlpha(0f);
        if (old != null) {
            old.recycle();
        }
    }
}
