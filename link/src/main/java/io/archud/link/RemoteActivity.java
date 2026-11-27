package io.archud.link;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.URLEncoder;

/**
 * REMOTE: Home, Back, arrows, OK, scroll, volume, typing and YouTube search for the TV. Every control goes to ARC on
 * the TV with the PIN, exactly like photos and audio. ARC on the TV is the only remote the TV's own remote service
 * knows (paired once, from any phone), so any number of phones can use it and none ever pairs with the TV.
 */
public class RemoteActivity extends JarvisActivity {
    private static final long POLL_MS = 2500;

    private float density;
    private TextView status;
    private TextView tvLine;
    private TextView info;
    private TextView pairButton;
    private EditText textField;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean polling;
    private volatile boolean busy;

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

        tvLine = Hud.label(this, "", 13, Hud.DIM, true);
        tvLine.setPadding(0, dp(8), 0, dp(8));
        col.addView(tvLine);

        col.addView(row(key("HOME", "home"), key("BACK", "back"), key("RECENT", "recents")), Hud.stack(this));
        col.addView(row(spacer(), key("UP", "navup"), spacer()), Hud.stack(this));
        col.addView(row(key("LEFT", "navleft"), key("OK", "ok"), key("RIGHT", "navright")), Hud.stack(this));
        col.addView(row(spacer(), key("DOWN", "navdown"), spacer()), Hud.stack(this));
        col.addView(row(key("VOL -", "voldown"), key("MUTE", "mute"), key("VOL +", "volup")), Hud.stack(this));
        col.addView(row(key("SCROLL UP", "scrollup"), key("SCROLL DOWN", "scrolldown")), Hud.stack(this));
        col.addView(row(key("SCREEN OFF", "screenoff"), key("SCREEN ON", "screenon")), Hud.stack(this));

        textField = Hud.field(this, "Type here, then send it to the TV or search YouTube with it",
                InputType.TYPE_CLASS_TEXT);
        col.addView(textField);
        col.addView(row(Hud.button(this, "TYPE ON THE TV", Hud.SOLID, true, this::sendText),
                Hud.button(this, "SEARCH YOUTUBE", Hud.SOLID, true, this::searchYoutube)), Hud.stack(this));

        pairButton = Hud.button(this, "ENABLE REAL KEYS (ONE TAP ON THE TV)", Hud.GOLDEN, false, this::enable);
        col.addView(pairButton, Hud.stack(this));

        info = Hud.label(this, "", 11, Hud.CYAN, false);
        info.setPadding(0, dp(14), 0, 0);
        col.addView(info);
        status = Hud.label(this, "", 11, Hud.DIM, false);
        status.setPadding(0, dp(4), 0, 0);
        col.addView(status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.start(this);
        polling = true;
        handler.post(pollTask);
    }

    /** Shows whether the paired TV is ON, in STANDBY (tap SCREEN ON to wake it) or OFFLINE, from its announcements. */
    private void showTv() {
        if (!Store.paired(this)) {
            tvLine.setText("");
            return;
        }
        String[] s = Finder.status(this);
        if (s == null) {
            tvLine.setTextColor(Hud.DIM);
            tvLine.setText("LOOKING FOR YOUR TV...");
            return;
        }
        String name = s[0].isEmpty() ? "TV" : s[0];
        switch (s[1]) {
            case "ON":
                tvLine.setTextColor(Hud.OK);
                tvLine.setText(name + "  ON");
                break;
            case "STANDBY":
                tvLine.setTextColor(Hud.GOLD);
                tvLine.setText(name + "  STANDBY - TAP SCREEN ON TO WAKE IT");
                break;
            default:
                tvLine.setTextColor(Hud.WARN);
                tvLine.setText(name + "  OFFLINE"
                        + ("-1".equals(s[2]) ? "" : " - LAST HEARD " + s[2] + " S AGO"));
                break;
        }
    }

    @Override
    protected void onPause() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            showTv();
            fetchState();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Asks ARC on the TV whether the module works, is paired, and what the volume is. */
    private void fetchState() {
        if (busy || !Store.paired(this)) {
            return;
        }
        busy = true;
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        final String pin = Store.pin(app);
        new Thread(() -> {
            try {
                TvClient.Reply r = TvClient.post(host, "/state", pin, "");
                if (r.ok()) {
                    final String text = r.text;
                    runOnUiThread(() -> showState(text));
                }
            } catch (IOException ignored) {
                // the TV may be off or asleep; the next poll tries again
            } finally {
                busy = false;
            }
        }, "arclink-state").start();
    }

    private static String value(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    private void showState(String text) {
        boolean module = !"0".equals(value(text, "module"));
        boolean paired = "1".equals(value(text, "paired"));
        pairButton.setVisibility(module ? View.VISIBLE : View.GONE);
        pairButton.setText(paired ? "REAL KEYS ARE ON (TAP TO CHECK AGAIN)" : "ENABLE REAL KEYS (ONE TAP ON THE TV)");
        StringBuilder sb = new StringBuilder();
        String vol = value(text, "volume");
        if (vol.matches("\\d+/\\d+") && !vol.startsWith("0/")) {
            sb.append("Volume ").append(vol.replace("/", " of "));
            if ("1".equals(value(text, "muted"))) {
                sb.append(" (muted)");
            }
        }
        String error = value(text, "error");
        if (!error.isEmpty()) {
            sb.append(sb.length() > 0 ? "  " : "").append("adb: ").append(error);
        }
        if (!module) {
            sb.append("This TV does not offer network adb, so the keys use ARC remote control.");
        } else if ("1".equals(value(text, "waiting"))) {
            sb.append(sb.length() > 0 ? "  " : "").append("On the TV: tick Always allow and tap ALLOW.");
        } else if (!paired) {
            sb.append(sb.length() > 0 ? "  " : "").append("Tap the gold button once so the keys work in every app, YouTube included.");
        }
        info.setText(sb.toString());
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
        return Hud.button(this, label, Hud.OUTLINE, true, () -> send("/key", "k=" + k + "&n=1"));
    }

    /** Sends one request to ARC on the TV and shows its reply on the status line. */
    private void send(final String path, final String body) {
        if (!Store.paired(this)) {
            status.setText("Pair with the TV first: go back and open the cog on the home screen.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        final String pin = Store.pin(app);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                TvClient.Reply r = TvClient.post(host, path, pin, body);
                msg = r.text.isEmpty() ? "The TV said " + r.code : r.text;
                ok = r.ok();
            } catch (IOException e) {
                msg = "Could not reach the TV: " + e.getMessage();
            }
            final String shown = msg;
            final boolean sent = ok;
            runOnUiThread(() -> {
                status.setText(shown);
                if (sent && (path.equals("/type") || path.equals("/ytsearch"))) {
                    textField.setText("");
                }
            });
        }, "arclink-remote").start();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** Types the box's text on the TV. With YouTube in front, ARC turns it into a search that replaces the old one. */
    private void sendText() {
        String value = textField.getText().toString();
        if (value.trim().isEmpty()) {
            status.setText("Type something first.");
            return;
        }
        send("/type", "text=" + enc(value));
    }

    /** Opens YouTube's results for the box's text on the TV, in any language. */
    private void searchYoutube() {
        String value = textField.getText().toString().trim();
        if (value.isEmpty()) {
            status.setText("Type what to search for first.");
            return;
        }
        send("/ytsearch", "q=" + enc(value));
    }

    // ------------------------------------------------------------------ one-time enabling of real keys

    /** Asks ARC on the TV to start the one-time authorization; the TV then shows an ALLOW dialog to tap. */
    private void enable() {
        if (!Store.paired(this)) {
            status.setText("Pair with the TV first: go back and open the cog on the home screen.");
            return;
        }
        send("/adbenable", "");
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
