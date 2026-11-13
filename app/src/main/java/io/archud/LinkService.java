package io.archud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps ARC LINK reachable: a foreground service holds the process while another app (SmartTube) is in front,
 * so Android does not stop it to free memory. Started by LinkServer.sync and at boot; runs only while the
 * ARC LINK switch is on.
 */
public class LinkService extends Service {
    private static final String CHANNEL = "arclink";
    private static final long CHECK_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            boolean wanted = Prefs.get(LinkService.this).getBoolean(Prefs.LINK_ON, false);
            if (!wanted) {
                stopSelf();
                return;
            }
            LinkServer.open(LinkService.this, true);
            main.postDelayed(this, CHECK_MS);
        }
    };

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LinkService.class));
        } catch (RuntimeException ignored) {
            // started from the background where Android refuses; the next app start or boot tries again
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LinkService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ARC LINK", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("ARC LINK is on")
                .build();
        startForeground(1, n);
        LinkServer.open(this, Prefs.get(this).getBoolean(Prefs.LINK_ON, false));
        main.removeCallbacks(check);
        main.postDelayed(check, CHECK_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(check);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
