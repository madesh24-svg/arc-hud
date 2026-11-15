package io.archud;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

/** BEACON REMOTE: pick one of the devices mapped in ARC SETTINGS > BEACON, then send it ARC LINK /key
 * commands. On-device shortcut to the same relay a paired caller gets over the network. */
public class BeaconActivity extends Activity {
    private static final int CYAN = 0xFF50DCFF;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int INK = 0xFF02081A;

    private float px;
    private volatile boolean sending;
    private BeaconMap.Entry chosen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;
        build();
    }

    @Override
    public void onBackPressed() {
        if (chosen != null) {
            chosen = null;
            build();
            return;
        }
        super.onBackPressed();
    }

    private void build() {
        List<BeaconMap.Entry> entries = BeaconMap.list(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(d(120), d(36), d(120), d(60));
        page.addView(text("BEACON REMOTE", 34, WHITE, true));

        if (entries.isEmpty()) {
            page.addView(text("No mapped devices yet. Add one in ARC SETTINGS > BEACON.", 15, DIM, false));
            scroll.addView(page);
            setContentView(scroll);
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, d(20), 0, 0);

        if (chosen == null) {
            page.addView(text("Press OK on a device, then BACK to pick a different one.", 14, DIM, false));
            for (BeaconMap.Entry e : entries) {
                button(list, e.name + "  (" + e.ip + ")", () -> {
                    chosen = e;
                    build();
                });
            }
        } else {
            page.addView(text("Sending to " + chosen.name + ". Press BACK to pick another device.", 14, DIM, false));
            button(list, "PLAY", () -> send("play", 0));
            button(list, "PAUSE", () -> send("pause", 0));
            button(list, "FORWARD 10s", () -> send("forward", 1));
            button(list, "REWIND 10s", () -> send("rewind", 1));
            button(list, "VOLUME UP", () -> send("up", 0));
            button(list, "VOLUME DOWN", () -> send("down", 0));
            button(list, "MUTE", () -> send("mute", 0));
            button(list, "UNMUTE", () -> send("unmute", 0));
        }
        page.addView(list);
        scroll.addView(page);
        setContentView(scroll);
    }

    private interface Action {
        void run();
    }

    private void button(LinearLayout list, String label, Action action) {
        TextView t = text(label, 16, WHITE, false);
        t.setPadding(d(16), d(10), d(16), d(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(rowBackground());
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, focused) -> t.setTextColor(focused ? INK : WHITE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = d(4);
        list.addView(t, lp);
    }

    private void send(String key, int n) {
        if (sending || chosen == null) {
            return;
        }
        final BeaconMap.Entry target = chosen;
        sending = true;
        new Thread(() -> {
            String result;
            try {
                BeaconClient.Reply r = BeaconClient.post(target.ip, "/key", target.pin, "k=" + key + "&n=" + n);
                result = r.ok() ? (r.text.isEmpty() ? "Done." : r.text) : "TV said: " + r.text;
            } catch (IOException e) {
                result = "Could not reach " + target.name + ": " + e.getMessage();
            } finally {
                sending = false;
            }
            final String shown = result;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_SHORT).show());
        }, "beacon-send").start();
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * px * 1.25f);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
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

    private int d(float v) {
        return Math.round(v * px);
    }
}
