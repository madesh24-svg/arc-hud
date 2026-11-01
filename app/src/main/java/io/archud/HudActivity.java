package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Full-screen HUD. Display text can be personalised once with intent extras, e.g.
 * am start -n io.archud/.HudActivity --es title "..." --es subtitle "..." --es salutation "..." [--es mac "..." --es ssid "..."]
 * OK or MENU on the remote opens screensaver setup. Nothing is changed without the user choosing YES.
 */
public class HudActivity extends Activity {
    static final String PREFS = "hud";
    private static final String[] KEYS = {"title", "subtitle", "salutation", "mac", "ssid"};
    private static final String NEVER_ASK = "ss_never";
    private static final long OFFER_DELAY_MS = 1500;

    private SharedPreferences prefs;
    private ScreensaverSetup setup;
    private SetupCard card;
    private boolean offeredThisSession;
    private boolean awaitingPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent intent = getIntent();
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : KEYS) {
            if (intent != null && intent.hasExtra(key)) {
                editor.putString(key, intent.getStringExtra(key));
                changed = true;
            }
        }
        if (changed) {
            editor.commit();
        }

        setup = new ScreensaverSetup(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(new HudView(this));
        card = new SetupCard(this);
        int width = (int) (560 * getResources().getDisplayMetrics().density);
        root.addView(card, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingPicker) {
            awaitingPicker = false;
            if (setup.isActive()) {
                done("ARC HUD is now the screensaver.");
            }
            return;
        }
        if (!offeredThisSession && !setup.isActive() && !prefs.getBoolean(NEVER_ASK, false)) {
            offeredThisSession = true;
            card.postDelayed(this::offer, OFFER_DELAY_MS);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean openKey = keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
        if (openKey && !card.isShowing()) {
            manage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (card.isShowing()) {
            card.hide();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- flow

    private void offer() {
        if (isFinishing()) {
            return;
        }
        card.show("SCREENSAVER SETUP",
                "Use this HUD as the TV screensaver?\nCurrent screensaver: " + setup.currentLabel(),
                new String[] {"YES", "NOT NOW", "NEVER ASK"},
                new SetupCard.Action[] {this::onYes, card::hide, this::neverAsk});
    }

    private void manage() {
        if (!setup.isActive()) {
            offer();
            return;
        }
        String idle = setup.idleNever() ? "The TV idle timer is off, so it will not start by itself."
                : "It starts after " + setup.idleMinutes() + " min without remote input.";
        if (setup.canWriteSystem()) {
            card.show("SCREENSAVER", "ARC HUD is the screensaver.\n" + idle,
                    new String[] {"CHANGE IDLE TIME", "RESTORE ORIGINAL", "CLOSE"},
                    new SetupCard.Action[] {this::chooseIdle, this::restore, card::hide});
        } else {
            card.show("SCREENSAVER", "ARC HUD is the screensaver.\n" + idle,
                    new String[] {"RESTORE ORIGINAL", "CLOSE"},
                    new SetupCard.Action[] {this::restore, card::hide});
        }
    }

    private void onYes() {
        Intent picker = setup.pickerIntent();
        if (picker != null) {
            card.hide();
            awaitingPicker = true;
            startActivity(picker);
            return;
        }
        if (!setup.canWriteSecure()) {
            info("SCREENSAVER SETUP", "This TV's maker does not allow apps to change the screensaver, "
                    + "and the TV has no screensaver settings screen.\nNothing was changed.");
            return;
        }
        if (!setup.apply()) {
            info("SCREENSAVER SETUP", "The TV refused the change.\nNothing was changed.");
            return;
        }
        if (setup.canWriteSystem()) {
            chooseIdle();
        } else if (setup.idleNever()) {
            done("ARC HUD is now the screensaver.\nThe TV idle timer is off, so it will not start by itself.");
        } else {
            done("ARC HUD is now the screensaver.\nIt starts after " + setup.idleMinutes() + " min without remote input.");
        }
    }

    private void chooseIdle() {
        card.show("IDLE TIME", "Start the HUD after the TV has had no remote input for:",
                new String[] {"5 MIN", "10 MIN", "15 MIN", "30 MIN"},
                new SetupCard.Action[] {() -> idle(5), () -> idle(10), () -> idle(15), () -> idle(30)});
    }

    private void idle(int minutes) {
        if (!setup.isActive()) {
            info("IDLE TIME", "The screensaver is not active, so the idle time was not changed.");
            return;
        }
        done(setup.setIdleMinutes(minutes)
                ? "ARC HUD is now the screensaver.\nIt starts after " + minutes + " min without remote input."
                : "ARC HUD is the screensaver, but the idle time could not be changed.");
    }

    private void done(String message) {
        if (setup.hasOriginal()) {
            card.show("DONE ✓", message, new String[] {"OK", "RESTORE ORIGINAL"},
                    new SetupCard.Action[] {card::hide, this::restore});
        } else {
            info("DONE ✓", message);
        }
    }

    private void restore() {
        info("RESTORE", setup.restore()
                ? "The original screensaver and idle time are back."
                : "Nothing to restore: ARC HUD did not change these settings.");
    }

    private void neverAsk() {
        prefs.edit().putBoolean(NEVER_ASK, true).apply();
        info("OK", "I will not ask again.\nPress OK on the HUD any time to set it up.");
    }

    private void info(String title, String message) {
        card.show(title, message, new String[] {"OK"}, new SetupCard.Action[] {card::hide});
    }
}
