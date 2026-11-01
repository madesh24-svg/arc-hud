package io.archud;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

/**
 * Full-screen HUD. Display text can be personalised once with intent extras, e.g.
 * am start -n io.archud/.HudActivity --es title "..." --es subtitle "..." --es salutation "..." [--es mac "..." --es ssid "..."]
 */
public class HudActivity extends Activity {
    static final String PREFS = "hud";
    private static final String[] KEYS = {"title", "subtitle", "salutation", "mac", "ssid"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
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
        setContentView(new HudView(this));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
