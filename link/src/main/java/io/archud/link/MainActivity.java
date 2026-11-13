package io.archud.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Pair with the TV once; send a video or a link; keep sharing alive; update. */
public class MainActivity extends JarvisActivity {
    private static final int REQUEST_MIC = 41;
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;
    private static final int PANEL = 0xFF0A2236;

    private float density;
    private EditText hostField;
    private EditText pinField;
    private Hud.Status status;
    private Hud.Status voice;
    private LinearLayout jarvisTile;
    private TextView spokenButton;
    private TextView sleepButton;
    private LinearLayout settingsPanel;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        Hud.bars(this);
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Hud.background(this));
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(col);
        setContentView(scroll);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        names.addView(Hud.label(this, "ARC LINK", 22, Hud.WHITE, true));
        names.addView(Hud.label(this, "PHONE TO TV  ·  v" + version(), 11, Hud.CYAN, false));
        brand.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout cog = new LinearLayout(this);
        cog.setGravity(Gravity.CENTER);
        cog.setBackground(new Hud.Frame(this, 8, Hud.PANEL, 0xDD0F3350, Hud.FRAME, Hud.CYAN));
        cog.addView(new Hud.Icon(this, Hud.ICON_COG, Hud.CYAN), new LinearLayout.LayoutParams(dp(24), dp(24)));
        cog.setClickable(true);
        cog.setFocusable(true);
        cog.setContentDescription("Settings");
        cog.setOnClickListener(v -> toggleSettings(scroll));
        brand.addView(cog, new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(brand);
        col.addView(Hud.rule(this));
        status = new Hud.Status(this);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = dp(6);
        col.addView(status, statusLp);
        voice = new Hud.Status(this);
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        voiceLp.bottomMargin = dp(14);
        col.addView(voice, voiceLp);

        // pairing section (built first so it can sit on top until the TV is paired)
        LinearLayout pair = Hud.panel(this, "PAIRING (ONCE)");
        pair.addView(Hud.label(this, "On the TV: ARC SETTINGS, then ARC LINK, switch ON. Enter its PIN here.",
                11, Hud.DIM, false));
        hostField = Hud.field(this, "TV address, e.g. 192.168.0.20", InputType.TYPE_CLASS_TEXT);
        hostField.setText(Store.host(this));
        pinField = Hud.field(this, "PIN shown on the TV", InputType.TYPE_CLASS_NUMBER);
        pinField.setText(Store.pin(this));
        pair.addView(hostField);
        pair.addView(pinField);
        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        pairRow.addView(Hud.button(this, "FIND THE TV", Hud.OUTLINE, true, this::findTv));
        pairRow.addView(Hud.button(this, "SAVE AND TEST", Hud.SOLID, true, this::saveAndTest));
        pair.addView(pairRow);

        // one box per feature area; each opens its own screen
        LinearLayout boxes = new LinearLayout(this);
        boxes.setOrientation(LinearLayout.HORIZONTAL);
        boxes.addView(Hud.tile(this, Hud.ICON_MEDIA, "MULTIMEDIA", true, this::openMultimedia));
        jarvisTile = Hud.tile(this, Hud.ICON_MIC, jarvisLabel(), true, this::toggleJarvis);
        boxes.addView(jarvisTile);

        LinearLayout jarvis = Hud.panel(this, "JARVIS (VOICE)");
        jarvis.addView(Hud.label(this, "Say \"Jarvis\" or \"arc arc\" while this app is open, then a command: "
                + "open SmartTube, play a song from Kantara, show my photos, next, stop, go to sleep.",
                11, Hud.DIM, false));
        spokenButton = Hud.button(this, spokenLabel(), Hud.OUTLINE, false, this::toggleSpoken);
        sleepButton = Hud.button(this, sleepLabel(), Hud.OUTLINE, false, this::cycleSleep);
        jarvis.addView(spokenButton);
        jarvis.addView(sleepButton);

        LinearLayout keep = Hud.panel(this, "KEEP IT WORKING");
        keep.addView(Hud.button(this, "ALLOW RUNNING IN THE BACKGROUND", Hud.GOLDEN, false, this::allowBackground));
        keep.addView(Hud.label(this, "Needed so the stream continues with the screen off. On Xiaomi/Poco also "
                + "allow Autostart for this app in the phone's app settings.", 11, Hud.DIM, false));
        keep.addView(Hud.label(this, "VERSION " + version(), 12, Hud.WHITE, true));
        keep.addView(Hud.button(this, "CHECK FOR UPDATE", Hud.OUTLINE, false, this::update));

        // pairing and upkeep live behind the cog; they open by themselves until the TV is paired
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.addView(pair, Hud.stack(this));
        settingsPanel.addView(jarvis, Hud.stack(this));
        settingsPanel.addView(keep, Hud.stack(this));
        settingsPanel.setVisibility(Store.paired(this) ? View.GONE : View.VISIBLE);

        col.addView(boxes);
        col.addView(settingsPanel);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refreshStatus();
    }

    // ---------------------------------------------------------------- actions

    private void refreshStatus() {
        if (Store.paired(this)) {
            status.set("PAIRED WITH THE TV AT " + Store.host(this), Hud.OK);
        } else {
            status.set("NOT PAIRED YET. TAP THE COG TO PAIR.", Hud.GOLD);
        }
    }

    @Override
    protected Hud.Status voiceLine() {
        return voice;
    }

    private String jarvisLabel() {
        return Store.voiceOn(this) ? "JARVIS ON" : "JARVIS OFF";
    }

    private String spokenLabel() {
        return Store.spoken(this) ? "SPOKEN REPLIES: ON" : "SPOKEN REPLIES: OFF";
    }

    private String sleepLabel() {
        int m = Store.sleepMinutes(this);
        return m == 0 ? "SLEEP AGAIN AFTER: NEVER" : "SLEEP AGAIN AFTER: " + m + " MIN";
    }

    private void toggleJarvis() {
        if (Store.voiceOn(this)) {
            setJarvis(false);
        } else if (Jarvis.hasMic(this)) {
            setJarvis(true);
        } else {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
    }

    private void setJarvis(boolean on) {
        Store.setVoiceOn(this, on);
        ((TextView) jarvisTile.getChildAt(1)).setText(jarvisLabel());
        Jarvis.settingsChanged();
    }

    private void toggleSpoken() {
        Store.setSpoken(this, !Store.spoken(this));
        spokenButton.setText(spokenLabel());
    }

    private void cycleSleep() {
        int m = Store.sleepMinutes(this);
        Store.setSleepMinutes(this, m == 0 ? 5 : (m == 5 ? 15 : 0));
        sleepButton.setText(sleepLabel());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setJarvis(true);
            } else {
                message("Jarvis cannot listen without microphone access. Allow it in the phone's app settings "
                        + "(Apps, ARC LINK, Permissions).");
            }
        }
    }

    private void toggleSettings(ScrollView scroll) {
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void needPairing() {
        settingsPanel.setVisibility(View.VISIBLE);
        message("Pair with the TV first: enter the PIN in PAIRING below (or tap the cog).");
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

    private void openMultimedia() {
        if (!Store.paired(this)) {
            needPairing();
            return;
        }
        startActivity(new Intent(this, MediaActivity.class));
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

}
