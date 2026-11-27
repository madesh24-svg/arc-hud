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
import android.os.PowerManager;
import android.net.wifi.WifiManager;

/**
 * Keeps ARC LINK reachable: a foreground service holds the process while another app (SmartTube) is in front,
 * so Android does not stop it to free memory. Started by LinkServer.sync and at boot; runs only while the
 * ARC LINK switch is on.
 */
public class LinkService extends Service {
    private static final String CHANNEL = "arclink";
    private static final long CHECK_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Held while ARC LINK is on: without them an idle box suspends after the screen goes off and drops off Wi-Fi. */
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            boolean wanted = Prefs.get(LinkService.this).getBoolean(Prefs.LINK_ON, false);
            if (!wanted) {
                release();
                stopSelf();
                return;
            }
            hold();
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
        hold();
        LinkServer.open(this, Prefs.get(this).getBoolean(Prefs.LINK_ON, false));
        main.removeCallbacks(check);
        main.postDelayed(check, CHECK_MS);
        return START_STICKY;
    }

    /** Keeps the CPU and the Wi-Fi radio awake so phones can always reach this TV, even with the screen off. */
    @SuppressWarnings("deprecation")
    private void hold() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "archud:link");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) {
                wake.acquire();
            }
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "archud:link");
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) {
                wifi.acquire();
            }
        } catch (RuntimeException ignored) {
            // no power or Wi-Fi service: the link still works while the box is awake
        }
    }

    private void release() {
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
            if (wifi != null && wifi.isHeld()) {
                wifi.release();
            }
        } catch (RuntimeException ignored) {
            // already released
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(check);
        release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
