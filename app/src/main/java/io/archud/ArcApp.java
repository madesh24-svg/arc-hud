package io.archud;

import android.app.Application;

/** Starts the voice-search watcher whenever the app's process starts. */
public class ArcApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VoiceSearchWatcher.start(this);
    }
}
