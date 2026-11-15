package io.archud;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

/** Starts the voice-search watcher and, if switched on, ARC LINK whenever the app's process starts.
 * Also runs the heartbeat on a repeating timer for as long as this process is alive. */
public class ArcApp extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            Heartbeat.sendInBackground();
            main.postDelayed(this, Heartbeat.INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        VoiceSearchWatcher.start(this);
        LinkServer.sync(this);
        main.postDelayed(heartbeat, Heartbeat.INTERVAL_MS);
    }
}
