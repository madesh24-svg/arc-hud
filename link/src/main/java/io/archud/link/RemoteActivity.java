package io.archud.link;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/**
 * REMOTE: Home, Back, arrows, OK, scroll, volume and screen on/off for the TV. Once paired with the TV's own remote
 * service the keys go through it and work in every app, YouTube included; until then (or if it fails) they go
 * through ARC remote control on the TV, which cannot steer apps that draw their own screens.
 */
public class RemoteActivity extends JarvisActivity {
    private float density;
    private TextView status;
    private TextView pairButton;
    private EditText textField;

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
        col.addView(row(key("VOL -", "voldown"), key("MUTE", "mute"), key("VOL +", "volup")), Hud.stack(this));
        col.addView(row(key("SCROLL UP", "scrollup"), key("SCROLL DOWN", "scrolldown")), Hud.stack(this));
        col.addView(row(key("SCREEN OFF", "screenoff"), key("SCREEN ON", "screenon")), Hud.stack(this));

        textField = Hud.field(this, "Type here, then send it to the TV or search YouTube with it",
                InputType.TYPE_CLASS_TEXT);
        col.addView(textField);
        col.addView(row(Hud.button(this, "TYPE ON THE TV", Hud.SOLID, true, this::sendText),
                Hud.button(this, "SEARCH YOUTUBE", Hud.SOLID, true, this::searchYoutube)), Hud.stack(this));

        pairButton = Hud.button(this, "PAIR TV REMOTE (WORKS IN YOUTUBE)", Hud.GOLDEN, false, this::pair);
        col.addView(pairButton, Hud.stack(this));

        status = Hud.label(this, "Pair the TV remote once so the keys work in every app. Until then they use ARC "
                + "remote control on the TV.", 11, Hud.DIM, false);
        status.setPadding(0, dp(14), 0, 0);
        col.addView(status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPairButton();
        AtvRemote.setListener(new AtvRemote.Listener() {
            @Override
            public void volume(final int level, final int max, final boolean muted) {
                runOnUiThread(() -> status.setText("Volume " + level + " of " + max + (muted ? " (muted)" : "")));
            }

            @Override
            public void app(String packageName) {
                // the TV also says which app is in front; not shown yet
            }
        });
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        if (Store.paired(app) && AtvRemote.paired(app, host)) {
            new Thread(() -> {
                try {
                    AtvRemote.connect(app, host);
                } catch (IOException ignored) {
                    // the first key press tries again and shows the reason
                }
            }, "atv-connect").start();
        }
    }

    @Override
    protected void onPause() {
        AtvRemote.setListener(null);
        super.onPause();
    }

    private void refreshPairButton() {
        boolean ok = Store.paired(this) && AtvRemote.paired(this, Store.host(this));
        pairButton.setText(ok ? "TV REMOTE PAIRED (TAP TO PAIR AGAIN)" : "PAIR TV REMOTE (WORKS IN YOUTUBE)");
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

    /** The name ARC remote control on the TV uses for a volume key. */
    private static String fallbackKey(String k) {
        switch (k) {
            case "volup":
                return "up";
            case "voldown":
                return "down";
            default:
                return k;
        }
    }

    private void send(final String k) {
        if (!Store.paired(this)) {
            status.setText("Pair with the TV first: go back and open the cog on the home screen.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        final String pin = Store.pin(app);
        final int code = AtvRemote.keycode(k);
        new Thread(() -> {
            String msg = null;
            if (code != 0 && AtvRemote.paired(app, host)) {
                try {
                    AtvRemote.key(app, host, code);
                    msg = "Done.";
                } catch (IOException e) {
                    msg = null; // fall back to ARC remote control on the TV
                }
            }
            if (msg == null) {
                try {
                    String n = k.startsWith("vol") ? "10" : "1";
                    TvClient.Reply r = TvClient.post(host, "/key", pin, "k=" + fallbackKey(k) + "&n=" + n);
                    msg = r.text.isEmpty() ? "The TV said " + r.code : r.text;
                } catch (IOException e) {
                    msg = "Could not reach the TV: " + e.getMessage();
                }
            }
            final String shown = msg;
            runOnUiThread(() -> status.setText(shown));
        }, "arclink-remote").start();
    }

    /** Types the box's text into the text field selected on the TV (needs the paired TV remote). */
    private void sendText() {
        final String value = textField.getText().toString();
        if (value.isEmpty()) {
            status.setText("Type something first.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        if (!Store.paired(app) || !AtvRemote.paired(app, host)) {
            status.setText("Pair the TV remote first (gold button below).");
            return;
        }
        // YouTube draws its own search box and appends typed text to what is there, so when it is in front the
        // text becomes a search link: that replaces the old query, submits it, and works for every language
        if (AtvRemote.YOUTUBE.equals(AtvRemote.foreground())) {
            runYoutubeSearch(app, host, value.trim());
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                // the text goes to whatever text box the TV has focused
                msg = AtvRemote.text(app, host, value)
                        ? "Typed on the TV."
                        : "Sent to the TV. Some apps, YouTube among them, do not confirm, so check the screen.";
            } catch (IOException e) {
                msg = "Could not reach the TV: " + e.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> {
                status.setText(shown);
                if (!shown.startsWith("Could not")) {
                    textField.setText("");
                }
            });
        }, "atv-text").start();
    }

    /** Opens YouTube's search results for the box's text on the TV (an app link, so no keyboard is needed). */
    private void searchYoutube() {
        final String value = textField.getText().toString().trim();
        if (value.isEmpty()) {
            status.setText("Type what to search for first.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        if (!Store.paired(app) || !AtvRemote.paired(app, host)) {
            status.setText("Pair the TV remote first (gold button below).");
            return;
        }
        runYoutubeSearch(app, host, value);
    }

    private void runYoutubeSearch(final Context app, final String host, final String value) {
        new Thread(() -> {
            String msg;
            try {
                // YouTube reads a "+" literally, so spaces must be %20
                String q = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
                AtvRemote.link(app, host, "https://www.youtube.com/results?search_query=" + q);
                msg = "Searching YouTube on the TV.";
            } catch (IOException e) {
                msg = "Could not reach the TV: " + e.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> {
                status.setText(shown);
                if (!shown.startsWith("Could not")) {
                    textField.setText("");
                }
            });
        }, "atv-search").start();
    }

    // ------------------------------------------------------------------ one-time pairing

    private void pair() {
        if (!Store.paired(this)) {
            status.setText("Pair with the TV first: go back and open the cog on the home screen.");
            return;
        }
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        status.setText("Starting pairing. A 6-character code will appear on the TV.");
        new Thread(() -> {
            try {
                final AtvRemote.Pairing p = new AtvRemote.Pairing(app, host);
                runOnUiThread(() -> askCode(p));
            } catch (IOException e) {
                final String m = e.getMessage();
                runOnUiThread(() -> status.setText("Could not start pairing: " + m));
            }
        }, "atv-pair").start();
    }

    private void askCode(final AtvRemote.Pairing p) {
        final EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setHint("6-character code");
        new AlertDialog.Builder(this)
                .setTitle("PAIR TV REMOTE")
                .setMessage("Type the code the TV is showing.")
                .setView(code)
                .setPositiveButton("PAIR", (d, w) -> finishPairing(p, code.getText().toString()))
                .setNegativeButton("CANCEL", (d, w) -> p.close())
                .setOnCancelListener(d -> p.close())
                .show();
    }

    private void finishPairing(final AtvRemote.Pairing p, final String code) {
        final Context app = getApplicationContext();
        final String host = Store.host(app);
        new Thread(() -> {
            String msg;
            try {
                p.finish(code);
                msg = "Paired. The keys now work in every app, YouTube included.";
                try {
                    AtvRemote.connect(app, host);
                } catch (IOException ignored) {
                    // connects on the first key press instead
                }
            } catch (IOException e) {
                msg = e.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> {
                status.setText(shown);
                refreshPairButton();
            });
        }, "atv-pair-finish").start();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
