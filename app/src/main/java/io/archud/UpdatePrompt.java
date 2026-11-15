package io.archud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Checks for an update and, if one exists, offers to install it right away. Used both automatically
 * (silently, from HomeActivity, every time the app's process starts) and from the "Check for update"
 * button in ARC SETTINGS (not silent: says so when already up to date or on failure).
 */
final class UpdatePrompt {
    private UpdatePrompt() {
    }

    static void check(Activity activity, boolean silent) {
        new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                activity.runOnUiThread(() -> offer(activity, r, silent));
            } catch (Exception e) {
                if (!silent) {
                    activity.runOnUiThread(() -> message(activity, "Could not check for updates.\n" + e.getMessage()));
                }
            }
        }, "update-check").start();
    }

    private static void offer(Activity activity, Updater.Release r, boolean silent) {
        if (r.versionCode <= versionCode(activity)) {
            if (!silent) {
                message(activity, "You have the latest version.");
            }
            return;
        }
        String size = r.size > 0 ? " (" + Math.max(1, r.size / 1024) + " KB)" : "";
        new AlertDialog.Builder(activity)
                .setMessage("Update to " + r.name + size + "?\nOnly from " + Updater.REPO + ", signed with the same key.")
                .setPositiveButton("UPDATE", (d, w) -> install(activity, r))
                .setNegativeButton(silent ? "LATER" : "CANCEL", null)
                .show();
    }

    private static void install(Activity activity, Updater.Release r) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            message(activity, "Allow ARC HUD to install updates on the next screen, then open the app again.");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        Toast.makeText(activity, "Downloading " + r.name + " ...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Updater.install(activity.getApplicationContext(), r);
            } catch (Exception e) {
                activity.runOnUiThread(() -> message(activity, "Update failed. Nothing was changed.\n" + e.getMessage()));
            }
        }, "update-install").start();
    }

    private static int versionCode(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void message(Activity activity, String text) {
        new AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show();
    }
}
