package io.archud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** ARC SETTINGS: everything the user can change, stored on the device only. */
public class SettingsActivity extends Activity {
    private static final String UPDATE_URL = "https://github.com/madesh24-svg/arc-hud/releases/latest/download/arc-hud.apk";
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;

    private interface Action {
        void run();
    }

    private float px;
    private SharedPreferences prefs;
    private ScreensaverSetup setup;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;
        prefs = Prefs.get(this);
        setup = new ScreensaverSetup(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(d(120), d(36), d(120), d(60));
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(0);
    }

    // ---------------------------------------------------------------- content

    private void build(int focusIndex) {
        list.removeAllViews();
        TextView title = text("ARC SETTINGS", 34, WHITE, true);
        list.addView(title);
        list.addView(text("Stored on this TV only. Press BACK to return.", 14, DIM, false));

        section("PERSONALISE");
        row("Your name (greeting)", value(Prefs.SALUTATION, "NOT SET"), () -> edit(Prefs.SALUTATION, "Your name", ""));
        row("Title", value(Prefs.TITLE, Prefs.DEFAULT_TITLE), () -> edit(Prefs.TITLE, "Title", Prefs.DEFAULT_TITLE));
        row("Subtitle", value(Prefs.SUBTITLE, Prefs.DEFAULT_SUBTITLE),
                () -> edit(Prefs.SUBTITLE, "Subtitle", Prefs.DEFAULT_SUBTITLE));

        section("HOME SCREEN");
        boolean status = prefs.getBoolean(Prefs.SHOW_STATUS, true);
        row("Live status line", status ? "ON" : "OFF", () -> {
            prefs.edit().putBoolean(Prefs.SHOW_STATUS, !status).apply();
            rebuild();
        });
        int hidden = Prefs.hidden(this).size();
        row("Hidden tiles", hidden == 0 ? "NONE" : hidden + " HIDDEN", hidden == 0 ? null : () -> confirm(
                "Show all hidden tiles again?", () -> {
                    Prefs.setHidden(this, new java.util.HashSet<String>());
                    rebuild();
                }));
        int pinned = Prefs.favs(this).size();
        row("Pinned to front", pinned == 0 ? "NONE" : pinned + " PINNED", pinned == 0 ? null : () -> confirm(
                "Unpin all tiles?", () -> {
                    Prefs.setFavs(this, new java.util.ArrayList<String>());
                    rebuild();
                }));
        row("Tip", "LONG-PRESS OK ON A TILE", null);

        section("NETWORK DISPLAY");
        row("Wi-Fi name", value(Prefs.SSID, "AUTO"), () -> edit(Prefs.SSID, "Wi-Fi name (empty = auto)", ""));
        row("MAC address", value(Prefs.MAC, "AUTO"), () -> edit(Prefs.MAC, "MAC address (empty = auto)", ""));

        section("SCREENSAVER");
        row("Current screensaver", setup.currentLabel(), null);
        if (setup.isActive()) {
            row("Restore original screensaver", "", setup.hasOriginal() ? this::restoreScreensaver : null);
        } else {
            row("Use ARC HUD as screensaver", "", this::useAsScreensaver);
        }
        row("Starts when idle", idleText(), null);

        section("ANIMATION");
        String anim = prefs.getString(Prefs.ANIM, "normal");
        row("Ring animation", anim.toUpperCase(), () -> {
            String next = "normal".equals(anim) ? "slow" : ("slow".equals(anim) ? "off" : "normal");
            prefs.edit().putString(Prefs.ANIM, next).apply();
            rebuild();
        });

        section("ABOUT");
        row("Version", version(), null);
        row("Download latest version", "OPENS BROWSER", this::update);
        row("Reset all settings", "", () -> confirm("Reset name, titles, hidden and pinned tiles, network values "
                + "and animation to defaults?", this::reset));

        focusRow(focusIndex);
    }

    private String idleText() {
        if (!setup.idleSafe()) {
            return "NOT SUPPORTED ON THIS TV";
        }
        return setup.idleNever() ? "NEVER (IDLE TIMER OFF)" : "AFTER " + setup.idleMinutes() + " MIN";
    }

    private String value(String key, String fallback) {
        String v = prefs.getString(key, "");
        return v.isEmpty() ? fallback : v;
    }

    private void rebuild() {
        View f = getCurrentFocus();
        build(f != null ? list.indexOfChild(f) : 0);
    }

    private void focusRow(int index) {
        View target = index > 0 && index < list.getChildCount() ? list.getChildAt(index) : null;
        if (target == null || !target.isFocusable()) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    target = list.getChildAt(i);
                    break;
                }
            }
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    // ---------------------------------------------------------------- actions

    private void edit(String key, String label, String defaultValue) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        boolean caps = Prefs.TITLE.equals(key) || Prefs.SUBTITLE.equals(key) || Prefs.SALUTATION.equals(key);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (caps ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS : 0));
        input.setText(prefs.getString(key, defaultValue));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setView(input)
                .setPositiveButton("SAVE", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) {
                        prefs.edit().remove(key).apply();
                    } else {
                        prefs.edit().putString(key, v).apply();
                    }
                    rebuild();
                })
                .setNeutralButton("DEFAULT", (d, w) -> {
                    prefs.edit().remove(key).apply();
                    rebuild();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void useAsScreensaver() {
        Intent picker = setup.pickerIntent();
        if (picker != null) {
            startActivity(picker);
            return;
        }
        if (!setup.canWriteSecure()) {
            message("This TV's maker does not allow apps to change the screensaver, and the TV has no "
                    + "screensaver settings screen. Nothing was changed.");
            return;
        }
        confirm("Set ARC HUD as the screensaver?", () -> {
            if (!setup.apply()) {
                message("The TV refused the change. Nothing was changed.");
            } else if (!setup.idleSafe()) {
                message("ARC HUD is now the screensaver. This TV switches itself off instead of starting "
                        + "screensavers when idle, so the idle timer was left unchanged.");
            } else {
                chooseIdle();
            }
            rebuild();
        });
    }

    private void chooseIdle() {
        if (!setup.canWriteSystem()) {
            message("ARC HUD is now the screensaver. The idle time could not be changed on this TV.");
            return;
        }
        final int[] minutes = {5, 10, 15, 30};
        new AlertDialog.Builder(this)
                .setTitle("Start after this much idle time")
                .setItems(new String[] {"5 MIN", "10 MIN", "15 MIN", "30 MIN"}, (d, which) -> {
                    if (setup.isActive() && setup.idleSafe()) {
                        setup.setIdleMinutes(minutes[which]);
                    }
                    rebuild();
                })
                .setNegativeButton("LEAVE UNCHANGED", null)
                .show();
    }

    private void restoreScreensaver() {
        confirm("Restore the original screensaver and idle time?", () -> {
            message(setup.restore() ? "The original screensaver and idle time are back." : "Nothing to restore.");
            rebuild();
        });
    }

    private void update() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL)));
        } catch (ActivityNotFoundException e) {
            message("No browser on this TV can open the download.");
        }
    }

    private void reset() {
        prefs.edit()
                .remove(Prefs.TITLE).remove(Prefs.SUBTITLE).remove(Prefs.SALUTATION)
                .remove(Prefs.SSID).remove(Prefs.MAC).remove(Prefs.SHOW_STATUS).remove(Prefs.ANIM)
                .remove(Prefs.HIDDEN).remove(Prefs.FAVS).remove(Prefs.NEVER_ASK)
                .apply();
        Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show();
        build(0);
    }

    private void confirm(String question, Action yes) {
        new AlertDialog.Builder(this)
                .setMessage(question)
                .setPositiveButton("YES", (d, w) -> yes.run())
                .setNegativeButton("NO", null)
                .show();
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- views

    private void section(String name) {
        TextView t = text(name, 16, GOLD, true);
        t.setPadding(0, d(26), 0, d(6));
        list.addView(t);
    }

    private void row(String label, String value, Action action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(d(16), d(10), d(16), d(10));
        TextView l = text(label, 16, action != null ? WHITE : DIM, false);
        TextView v = text(value, 16, CYAN, true);
        v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (action != null) {
            r.setFocusable(true);
            r.setClickable(true);
            r.setBackground(rowBackground());
            r.setOnClickListener(x -> action.run());
            r.setOnFocusChangeListener((x, focused) -> {
                l.setTextColor(focused ? INK : WHITE);
                v.setTextColor(focused ? INK : CYAN);
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = d(4);
        list.addView(r, lp);
    }

    private StateListDrawable rowBackground() {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0xFF0A2236);
        idle.setCornerRadius(d(6));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(CYAN);
        focused.setCornerRadius(d(6));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[] {}, idle);
        return s;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * px * 1.25f);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private int d(float v) {
        return Math.round(v * px);
    }
}
