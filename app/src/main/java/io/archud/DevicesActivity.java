package io.archud;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Devices on the local network, refreshed every minute while this screen is open. */
public class DevicesActivity extends Activity implements NetScanner.Listener {
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int OK = 0xFF78FFAA;
    private static final int INK = 0xFF02081A;

    private float px;
    private NetScanner scanner;
    private LinearLayout list;
    private TextView summary;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;
        scanner = NetScanner.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(d(80), d(30), d(80), d(40));
        page.addView(text("NETWORK DEVICES", 32, WHITE, true));
        summary = text("Scanning your home network ...", 15, DIM, false);
        page.addView(summary);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, d(16), 0, 0);
        page.addView(list);
        page.addView(text("Local network only. Names come from the devices and your router; manufacturer "
                + "names from this app's release data. Refreshes every minute while open.", 12, DIM, false));
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanner.subscribe(this);
    }

    @Override
    protected void onPause() {
        scanner.unsubscribe(this);
        super.onPause();
    }

    @Override
    public void onDevices(List<NetScanner.Device> devices) {
        View focused = getCurrentFocus();
        int focusIndex = focused != null ? list.indexOfChild(focused) : -1;
        long now = System.currentTimeMillis();
        int fresh = 0;
        list.removeAllViews();
        for (NetScanner.Device dev : devices) {
            if (dev.isNew) {
                fresh++;
            }
            list.addView(row(dev, now));
        }
        StringBuilder s = new StringBuilder();
        s.append(devices.size()).append(" ONLINE");
        if (fresh > 0) {
            s.append("  ·  ").append(fresh).append(" NEW");
        }
        if (scanner.routerUptime != null) {
            s.append("  ·  ROUTER UP ").append(scanner.routerUptime);
        }
        if (scanner.publicIp != null) {
            s.append("  ·  PUBLIC IP ").append(scanner.publicIp);
        }
        if (scanner.lastScan > 0) {
            s.append("  ·  UPDATED ").append(clock.format(new Date(scanner.lastScan)));
        }
        summary.setText(s.toString());
        if (list.getChildCount() > 0) {
            int i = focusIndex >= 0 && focusIndex < list.getChildCount() ? focusIndex : 0;
            list.getChildAt(i).requestFocus();
        }
    }

    private View row(NetScanner.Device dev, long now) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(d(16), d(8), d(16), d(8));
        r.setFocusable(true);
        r.setBackground(rowBackground());

        String title = dev.display() + (dev.self ? "" : (dev.gateway ? "  (ROUTER)" : ""));
        TextView name = text(title + (dev.isNew ? "   ● NEW" : ""), 17, dev.isNew ? GOLD : WHITE, true);
        StringBuilder meta = new StringBuilder(dev.ip);
        if (!dev.mac.isEmpty()) {
            meta.append("   ").append(dev.mac);
        }
        if (!dev.vendor.isEmpty() && !dev.vendor.equals(dev.display())) {
            meta.append("   ").append(dev.vendor);
        }
        if (!dev.kind.isEmpty()) {
            meta.append("   ").append(dev.kind);
        }
        if (!dev.model.isEmpty() && !dev.model.equals(dev.display())) {
            meta.append("   ").append(dev.model);
        }
        meta.append("   ONLINE ").append(Stats.duration(Math.max(0, now - dev.firstSeen)));
        TextView info = text(meta.toString(), 13, dev.self ? OK : CYAN, false);
        r.addView(name);
        r.addView(info);
        r.setOnFocusChangeListener((v, f) -> {
            name.setTextColor(f ? INK : (dev.isNew ? GOLD : WHITE));
            info.setTextColor(f ? INK : (dev.self ? OK : CYAN));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = d(4);
        r.setLayoutParams(lp);
        return r;
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
