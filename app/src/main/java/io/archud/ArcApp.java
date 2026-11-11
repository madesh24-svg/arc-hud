package io.archud;

import android.app.Application;

/** Starts the voice-search watcher and, if switched on, ARC LINK whenever the app's process starts. */
public class ArcApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VoiceSearchWatcher.start(this);
        LinkServer.sync(this);
    }
}
