package io.archud.link;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** SECURITY CHECK: scans the home network the phone is on and lists what is exposed, device by device. */
public class SecurityActivity extends JarvisActivity {
    /** Start the scan as soon as the screen opens (used by the voice command). */
    static final String EXTRA_AUTO = "auto";

    private Hud.Status status;
    private TextView scanButton;
    private LinearLayout results;
    private AtomicBoolean cancel = new AtomicBoolean(true);
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        names.addView(Hud.label(this, "SECURITY CHECK", 22, Hud.WHITE, true));
        names.addView(Hud.label(this, "YOUR HOME NETWORK", 11, Hud.CYAN, false));
        brand.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        brand.addView(new Hud.Ring(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(brand);
        col.addView(Hud.rule(this));

        LinearLayout intro = Hud.panel(this, "WHAT THIS DOES");
        intro.addView(Hud.label(this, "Looks at the devices on the Wi-Fi this phone is on and lists what they expose: "
                + "risky open ports, logins that are missing or unencrypted, old software and what your router has "
                + "opened to the internet. It only sends harmless probes, never logs in, never changes anything and "
                + "never leaves your own network. Results are exposures to review, not proven vulnerabilities.",
                11, Hud.DIM, false));
        col.addView(intro, Hud.stack(this));

        scanButton = Hud.button(this, "SCAN MY NETWORK", Hud.SOLID, false, this::toggleScan);
        col.addView(scanButton);
        status = new Hud.Status(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        sp.bottomMargin = dp(10);
        col.addView(status, sp);
        status.set("READY. THE SCAN TAKES ABOUT A MINUTE.", Hud.DIM);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);

        if (getIntent().getBooleanExtra(EXTRA_AUTO, false)) {
            scroll.post(this::toggleScan);
        }
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        super.onDestroy();
    }

    private int dp(int v) {
        return Hud.dp(this, v);
    }

    private void toggleScan() {
        if (running) {
            cancel.set(true);
            status.set("STOPPING ...", Hud.GOLD);
            return;
        }
        running = true;
        cancel = new AtomicBoolean(false);
        final AtomicBoolean mine = cancel;
        results.removeAllViews();
        scanButton.setText("STOP");
        status.set("STARTING ...", Hud.CYAN);
        new Thread(new Runnable() {
            @Override
            public void run() {
                SecurityScan.run(new SecurityScan.Listener() {
                    @Override
                    public void progress(final String text) {
                        runOnUiThread(() -> {
                            if (running) {
                                status.set(text, Hud.CYAN);
                            }
                        });
                    }

                    @Override
                    public void done(final List<SecurityScan.Host> hosts, final String base) {
                        runOnUiThread(() -> finished(hosts, base, mine.get()));
                    }
                }, mine);
            }
        }, "arc-security-scan").start();
    }

    private void finished(List<SecurityScan.Host> hosts, String base, boolean stopped) {
        running = false;
        scanButton.setText("SCAN AGAIN");
        if (isDestroyed()) {
            return;
        }
        if (hosts == null) {
            status.set("NOT ON A HOME WI-FI. CONNECT THE PHONE TO YOUR WI-FI FIRST.", Hud.WARN);
            return;
        }
        int fix = 0;
        int review = 0;
        for (SecurityScan.Host h : hosts) {
            int w = h.worst();
            if (w == SecurityScan.FIX) {
                fix++;
            } else if (w == SecurityScan.REVIEW) {
                review++;
            }
        }
        String tv = Store.host(this);
        for (SecurityScan.Host h : hosts) {
            results.addView(card(h, tv), Hud.stack(this));
        }
        String summary = hosts.size() + " DEVICES ON " + base + "0.  " + fix + " TO FIX,  " + review + " TO REVIEW.";
        if (stopped) {
            summary = "STOPPED.  " + summary;
        }
        status.set(summary, fix > 0 ? Hud.WARN : (review > 0 ? Hud.GOLD : Hud.OK));
    }

    private LinearLayout card(SecurityScan.Host h, String tvIp) {
        String title = h.ip;
        String who = h.name;
        if (h.ip.equals(tvIp)) {
            who = "THE TV (ARC)";
        } else if (h.ip.endsWith(".1") && who.isEmpty()) {
            who = "ROUTER";
        }
        if (!who.isEmpty()) {
            title += "   " + who.toUpperCase(java.util.Locale.ROOT);
        }
        LinearLayout p = Hud.panel(this, title);
        int w = h.worst();
        int fixes = 0;
        int reviews = 0;
        for (SecurityScan.Finding f : h.findings) {
            if (f.level == SecurityScan.FIX) {
                fixes++;
            } else if (f.level == SecurityScan.REVIEW) {
                reviews++;
            }
        }
        String grade = w == SecurityScan.FIX ? "FIX  " + fixes + " ISSUE" + (fixes == 1 ? "" : "S")
                + (reviews > 0 ? ",  " + reviews + " TO REVIEW" : "")
                : (w == SecurityScan.REVIEW ? "REVIEW  " + reviews + " THING" + (reviews == 1 ? "" : "S")
                : (h.open.isEmpty() ? "NO OPEN PORTS FOUND" : "NOTHING RISKY FOUND"));
        p.addView(Hud.label(this, grade, 12, w == SecurityScan.FIX ? Hud.WARN
                : (w == SecurityScan.REVIEW ? Hud.GOLD : Hud.OK), true));
        if (!h.open.isEmpty()) {
            StringBuilder ports = new StringBuilder("Open ports: ");
            for (int i = 0; i < h.open.size(); i++) {
                ports.append(i > 0 ? ", " : "").append(h.open.get(i));
            }
            p.addView(Hud.label(this, ports.toString(), 11, Hud.DIM, false));
        }
        List<SecurityScan.Finding> sorted = new ArrayList<>(h.findings);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.level, a.level));
        for (SecurityScan.Finding f : sorted) {
            int color = f.level == SecurityScan.FIX ? Hud.WARN : (f.level == SecurityScan.REVIEW ? Hud.GOLD
                    : Hud.DIM);
            TextView t = Hud.label(this, "• " + f.text, 12, color, false);
            t.setPadding(0, dp(6), 0, 0);
            p.addView(t);
            if (f.fix != null && f.level >= SecurityScan.REVIEW) {
                p.addView(Hud.label(this, "   Fix: " + f.fix, 11, Hud.DIM, false));
            }
        }
        return p;
    }
}
