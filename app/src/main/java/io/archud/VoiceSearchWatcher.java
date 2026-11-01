package io.archud;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Notices a new voice search. The phone-remote server stores the spoken text in the system setting
 * "phoneremoteyoutubeSearchKey" just before it opens its own results screen; this watcher then opens
 * ARC SEARCH on top. It only reads that one setting, changes nothing, and can be switched off in
 * ARC SETTINGS. It lives as long as the app's process (the home screen keeps it running).
 */
final class VoiceSearchWatcher extends ContentObserver {
    private static final String KEY = "phoneremoteyoutubeSearchKey";
    private static final long OPEN_DELAY_MS = 700;
    private static final long REPEAT_WINDOW_MS = 5000;
    private static VoiceSearchWatcher instance;

    private final Context ctx;
    private final Handler main;
    private String last;
    private long lastAt;

    private VoiceSearchWatcher(Context context, Handler handler) {
        super(handler);
        ctx = context;
        main = handler;
        last = current();
    }

    static synchronized void start(Context app) {
        if (instance != null) {
            return;
        }
        instance = new VoiceSearchWatcher(app, new Handler(Looper.getMainLooper()));
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY), false, instance);
    }

    private String current() {
        String v = Settings.Global.getString(ctx.getContentResolver(), KEY);
        return v == null ? "" : v.trim();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        final String query = current();
        if (!Prefs.get(ctx).getBoolean(Prefs.VOICE_TAKEOVER, true)) {
            last = query;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (query.isEmpty() || (query.equals(last) && now - lastAt < REPEAT_WINDOW_MS)) {
            return;
        }
        last = query;
        lastAt = now;
        // wait a moment so ARC SEARCH ends up on top of the vendor's own results screen
        main.postDelayed(() -> ctx.startActivity(new Intent(ctx, SearchActivity.class)
                .putExtra(SearchActivity.EXTRA_QUERY, query)
                .putExtra(SearchActivity.EXTRA_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)), OPEN_DELAY_MS);
    }
}
