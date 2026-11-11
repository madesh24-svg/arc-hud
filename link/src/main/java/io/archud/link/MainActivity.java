package io.archud.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Pair with the TV once; send a video or a link; keep sharing alive; update. */
public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 7;
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;
    private static final int PANEL = 0xFF0A2236;

    private float density;
    private EditText hostField;
    private EditText pinField;
    private EditText linkField;
    private TextView status;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(col);
        setContentView(scroll);

        col.addView(text("ARC LINK", 26, WHITE, true));
        col.addView(text("Send videos and links from this phone to the TV.", 14, DIM, false));
        status = text("", 15, CYAN, true);
        status.setPadding(0, dp(14), 0, 0);
        col.addView(status);

        col.addView(heading("1. PAIR WITH THE TV (ONCE)"));
        col.addView(text("On the TV: ARC SETTINGS, then ARC LINK, switch ON. Enter its PIN here.", 13, DIM, false));
        hostField = field("TV address, e.g. 192.168.0.20", InputType.TYPE_CLASS_TEXT);
        hostField.setText(Store.host(this));
        pinField = field("PIN shown on the TV", InputType.TYPE_CLASS_NUMBER);
        pinField.setText(Store.pin(this));
        col.addView(hostField);
        col.addView(pinField);
        col.addView(button("FIND THE TV", this::findTv));
        col.addView(button("SAVE AND TEST", this::saveAndTest));

        col.addView(heading("2. SEND"));
        col.addView(button("PICK A VIDEO TO PLAY ON THE TV", this::pickVideo));
        col.addView(text("The video stays on this phone and streams to the TV. Keep Wi-Fi on.", 13, DIM, false));
        linkField = field("Paste a YouTube link or web address", InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(linkField);
        col.addView(button("SEND LINK TO THE TV", this::sendLink));
        col.addView(text("Tip: in YouTube, Chrome or Gallery use Share, then ARC LINK (TV).", 13, DIM, false));

        col.addView(heading("3. KEEP IT WORKING"));
        col.addView(button("ALLOW RUNNING IN THE BACKGROUND", this::allowBackground));
        col.addView(text("Needed so the stream continues with the screen off. On Xiaomi/Poco also allow "
                + "Autostart for this app in the phone's app settings.", 13, DIM, false));

        col.addView(heading("ABOUT"));
        col.addView(text("Version " + version(), 14, WHITE, false));
        col.addView(button("CHECK FOR UPDATE", this::update));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refreshStatus();
    }

    // ---------------------------------------------------------------- actions

    private void refreshStatus() {
        status.setText(Store.paired(this) ? "Paired with the TV at " + Store.host(this) : "Not paired yet");
    }

    private void findTv() {
        if (busy) {
            return;
        }
        busy = true;
        Toast.makeText(this, "Looking for the TV...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String found = TvClient.find();
            runOnUiThread(() -> {
                busy = false;
                if (found == null) {
                    message("The TV was not found. Check that ARC LINK is ON on the TV and that this phone "
                            + "is on the same Wi-Fi, or type the TV address yourself.");
                } else {
                    hostField.setText(found);
                    Toast.makeText(this, "Found the TV at " + found, Toast.LENGTH_LONG).show();
                }
            });
        }, "arclink-find").start();
    }

    private void saveAndTest() {
        final String host = hostField.getText().toString().trim();
        final String pin = pinField.getText().toString().trim();
        if (!TvClient.isLocalIp(host)) {
            message("Enter the TV's home-network address, like 192.168.0.20.");
            return;
        }
        if (pin.length() != 4) {
            message("The PIN has 4 digits and is shown on the TV in ARC SETTINGS.");
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        new Thread(() -> {
            String result;
            try {
                // an empty link is rejected after the PIN is checked: 400 means the PIN was accepted
                TvClient.Reply r = TvClient.post(host, "/open", pin, "url=");
                if (r.code == 400) {
                    Store.save(this, host, pin);
                    result = "Paired with the TV.";
                } else if (r.code == 403) {
                    result = "Wrong PIN. Check the PIN on the TV.";
                } else if (r.code == 429) {
                    result = "Too many wrong PINs. Wait a minute and try again.";
                } else {
                    result = "The TV answered " + r.code + ".";
                }
            } catch (IOException e) {
                result = "Could not reach the TV: " + e.getMessage();
            }
            final String shown = result;
            runOnUiThread(() -> {
                busy = false;
                refreshStatus();
                message(shown);
            });
        }, "arclink-pair").start();
    }

    private void pickVideo() {
        if (!Store.paired(this)) {
            message("Pair with the TV first (step 1).");
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        startActivityForResult(pick, PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // not every provider offers a persistable grant; the one-time grant is enough
            }
            Sender.video(this, uri);
        }
    }

    private void sendLink() {
        if (!Store.paired(this)) {
            message("Pair with the TV first (step 1).");
            return;
        }
        Sender.link(this, linkField.getText().toString());
    }

    private void allowBackground() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                message("Open the phone's battery settings and set ARC LINK to 'No restrictions'.");
            }
        }
    }

    private void update() {
        if (busy) {
            return;
        }
        busy = true;
        Toast.makeText(this, "Checking for an update...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                runOnUiThread(() -> {
                    busy = false;
                    offerUpdate(r);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    busy = false;
                    message("Could not check for updates.\n" + e.getMessage());
                });
            }
        }, "arclink-update").start();
    }

    private void offerUpdate(Updater.Release r) {
        if (r.versionCode <= versionCode()) {
            message("You have the latest version (" + version() + ").");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Update to " + r.name + "?\nOnly from " + Updater.REPO + ", signed with the same key.")
                .setPositiveButton("UPDATE", (d, w) -> {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        message("Allow ARC LINK to install updates on the next screen, then press Update again.");
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                        return;
                    }
                    Toast.makeText(this, "Downloading " + r.name + "...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            Updater.install(getApplicationContext(), r);
                        } catch (IOException e) {
                            runOnUiThread(() -> message("Update failed. Nothing was changed.\n" + e.getMessage()));
                        }
                    }, "arclink-install").start();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
    }

    // ---------------------------------------------------------------- views

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 15, GOLD, true);
        t.setPadding(0, dp(26), 0, dp(6));
        return t;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(WHITE);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackgroundColor(PANEL);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(INK);
        b.setBackgroundColor(CYAN);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setOnClickListener((View v) -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }
}
