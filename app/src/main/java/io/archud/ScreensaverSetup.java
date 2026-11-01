package io.archud;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.provider.Settings;

/**
 * Reads and (only after the user agrees) changes the system screensaver. Changing it directly needs
 * WRITE_SECURE_SETTINGS, which only the device owner can grant; without it the app uses the system
 * picker when the device has one, and otherwise explains that the device does not allow it.
 */
final class ScreensaverSetup {
    private static final String KEY_COMPONENTS = "screensaver_components";
    private static final String KEY_ENABLED = "screensaver_enabled";
    private static final String KEY_ON_SLEEP = "screensaver_activate_on_sleep";
    private static final String ORIG_COMPONENTS = "orig_components";
    private static final String ORIG_ENABLED = "orig_enabled";
    private static final String ORIG_TIMEOUT = "orig_timeout";
    private static final int NEVER_THRESHOLD_MS = 24 * 3600 * 1000;

    private final Context ctx;
    private final ContentResolver cr;
    private final SharedPreferences prefs;
    private final ComponentName ours;

    ScreensaverSetup(Context context) {
        ctx = context;
        cr = context.getContentResolver();
        prefs = context.getSharedPreferences(HudActivity.PREFS, Context.MODE_PRIVATE);
        ours = new ComponentName(context, HudDream.class);
    }

    boolean isActive() {
        return ours.equals(firstComponent()) && Settings.Secure.getInt(cr, KEY_ENABLED, 1) == 1;
    }

    String currentLabel() {
        ComponentName cn = firstComponent();
        if (cn == null) {
            return "NONE";
        }
        if (ours.equals(cn)) {
            return "ARC HUD";
        }
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getServiceInfo(cn, 0).loadLabel(pm).toString().toUpperCase();
        } catch (PackageManager.NameNotFoundException e) {
            return cn.getPackageName().toUpperCase() + " (MISSING)";
        }
    }

    boolean idleNever() {
        int t = timeoutMs();
        return t <= 0 || t >= NEVER_THRESHOLD_MS;
    }

    int idleMinutes() {
        return timeoutMs() / 60000;
    }

    /**
     * Android only starts a screensaver on idle while the device reports external power; otherwise it
     * goes straight to sleep. Some TVs report no power source, so on those an idle timer would switch
     * the TV off instead of showing the HUD. Only offer idle times where this is safe.
     */
    boolean idleSafe() {
        Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    Intent pickerIntent() {
        Intent i = new Intent(Settings.ACTION_DREAM_SETTINGS);
        return i.resolveActivity(ctx.getPackageManager()) != null ? i : null;
    }

    boolean canWriteSecure() {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    boolean canWriteSystem() {
        return Settings.System.canWrite(ctx);
    }

    /** Makes this app the screensaver and returns true only if the device reports it as active afterwards. */
    boolean apply() {
        saveOriginalsOnce();
        try {
            Settings.Secure.putString(cr, KEY_COMPONENTS, ours.flattenToString());
            Settings.Secure.putInt(cr, KEY_ENABLED, 1);
            Settings.Secure.putInt(cr, KEY_ON_SLEEP, 1);
        } catch (SecurityException e) {
            return false;
        }
        return isActive();
    }

    /** Sets the idle time; callers only do this after apply() confirmed the screensaver is active. */
    boolean setIdleMinutes(int minutes) {
        if (!canWriteSystem()) {
            return false;
        }
        try {
            return Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60000);
        } catch (SecurityException e) {
            return false;
        }
    }

    boolean hasOriginal() {
        return prefs.contains(ORIG_COMPONENTS);
    }

    boolean restore() {
        if (!hasOriginal()) {
            return false;
        }
        try {
            Settings.Secure.putString(cr, KEY_COMPONENTS, prefs.getString(ORIG_COMPONENTS, ""));
            Settings.Secure.putInt(cr, KEY_ENABLED, prefs.getInt(ORIG_ENABLED, 1));
            if (prefs.contains(ORIG_TIMEOUT) && canWriteSystem()) {
                Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, prefs.getInt(ORIG_TIMEOUT, Integer.MAX_VALUE));
            }
        } catch (SecurityException e) {
            return false;
        }
        prefs.edit().remove(ORIG_COMPONENTS).remove(ORIG_ENABLED).remove(ORIG_TIMEOUT).apply();
        return true;
    }

    private void saveOriginalsOnce() {
        if (hasOriginal()) {
            return;
        }
        String current = Settings.Secure.getString(cr, KEY_COMPONENTS);
        prefs.edit()
                .putString(ORIG_COMPONENTS, current == null ? "" : current)
                .putInt(ORIG_ENABLED, Settings.Secure.getInt(cr, KEY_ENABLED, 1))
                .putInt(ORIG_TIMEOUT, timeoutMs())
                .apply();
    }

    private int timeoutMs() {
        return Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1);
    }

    private ComponentName firstComponent() {
        String v = Settings.Secure.getString(cr, KEY_COMPONENTS);
        if (v == null || v.isEmpty()) {
            return null;
        }
        return ComponentName.unflattenFromString(v.split(",")[0]);
    }
}
