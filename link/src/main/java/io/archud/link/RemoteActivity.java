package io.archud.link;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/** REMOTE: Home, Back, arrows, OK, scroll and screen on/off for any app on the TV (needs ARC remote control on). */
public class RemoteActivity extends JarvisActivity {
    private float density;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        Hud.bars(this);
        ScrollView scroll = new ScrollView(this);
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
        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER);
        back.setBackground(new Hud.Frame(this, 8, Hud.PANEL, 0xDD0F3350, Hud.FRAME, Hud.CYAN));
        back.addView(new Hud.Icon(this, Hud.ICON_BACK, Hud.CYAN), new LinearLayout.LayoutParams(dp(24), dp(24)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        brand.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        names.addView(Hud.label(this, "REMOTE", 22, Hud.WHITE, true));
        names.addView(Hud.label(this, "DRIVE ANY APP ON THE TV", 11, Hud.CYAN, false));
        brand.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(brand);
        col.addView(Hud.rule(this));

        col.addView(row(key("HOME", "home"), key("BACK", "back"), key("RECENT", "recents")), Hud.stack(this));
        col.addView(row(spacer(), key("UP", "navup"), spacer()), Hud.stack(this));
        col.addView(row(key("LEFT", "navleft"), key("OK", "ok"), key("RIGHT", "navright")), Hud.stack(this));
        col.addView(row(spacer(), key("DOWN", "navdown"), spacer()), Hud.stack(this));
        col.addView(row(key("SCROLL UP", "scrollup"), key("SCROLL DOWN", "scrolldown")), Hud.stack(this));
        col.addView(row(key("SCREEN OFF", "screenoff"), key("SCREEN ON", "screenon")), Hud.stack(this));

        status = Hud.label(this, "Needs ARC remote control switched on in the TV's Settings, Accessibility, Services.",
                11, Hud.DIM, false);
        status.setPadding(0, dp(14), 0, 0);
        col.addView(status);
    }

    private LinearLayout row(View... items) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : items) {
            r.addView(v);
        }
        return r;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private TextView key(String label, final String k) {
        return Hud.button(this, label, Hud.OUTLINE, true, () -> send(k));
    }

    private void send(final String k) {
        if (!Store.paired(this)) {
            status.setText("Pair with the TV first: go back and open the cog on the home screen.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        final String pin = Store.pin(app);
        new Thread(() -> {
            String msg;
            try {
                TvClient.Reply r = TvClient.post(host, "/key", pin, "k=" + k + "&n=1");
                msg = r.text.isEmpty() ? "The TV said " + r.code : r.text;
            } catch (IOException e) {
                msg = "Could not reach the TV: " + e.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> status.setText(shown));
        }, "arclink-remote").start();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
