package io.archud;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small in-memory thumbnail loader (downloads go through SafeHttp, so only the allowlisted image host). */
final class Thumbs {
    private static final int CACHE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 400 * 1024;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Thumbs() {
    }

    static void load(final String url, final ImageView view, final int targetWidthPx) {
        view.setTag(url);
        view.setImageDrawable(null);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        POOL.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = decode(SafeHttp.get(url, MAX_IMAGE_BYTES), targetWidthPx);
            } catch (Exception ignored) {
                // no thumbnail is fine; the row still works
            }
            if (decoded == null) {
                return;
            }
            final Bitmap bitmap = decoded;
            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap decode(byte[] data, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) {
            sample *= 2;
        }
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, real);
    }
}
