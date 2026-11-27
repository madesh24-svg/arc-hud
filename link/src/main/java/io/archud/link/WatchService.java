package io.archud.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps this phone listening for the paired TV's announcements (see Finder) while the app is in the background, so
 * the phone always knows whether the TV is ON, in STANDBY or OFFLINE and where it is on the network. Started from
 * the app's own screens only, never from the background. It reads nothing and sends nothing; it only listens.
 */
public class WatchService extends Service {
    private static final String CHANNEL = "arcwatch";

    /** Starts the listener if this phone is paired; harmless if it is already running or Android refuses. */
    static void start(Context c) {
        if (!Store.paired(c)) {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, WatchService.class));
        } catch (RuntimeException ignored) {
            // Android refused a background start: the app tries again the next time one of its screens opens
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Watching your TV",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ARC LINK is watching for your TV")
                .build();
        startForeground(2, n);
        Finder.ensure(this);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
