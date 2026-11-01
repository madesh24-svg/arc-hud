package io.archud;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Launcher home: live HUD background with Inputs and Apps rows on top. */
public class HomeActivity extends Activity {
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int TILE_BG = 0xCC0A2236;
    private static final int TILE_EDGE = 0xFF2A7A9E;

    private float px;
    private LinearLayout inputsRow;
    private LinearLayout appsRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;

        FrameLayout root = new FrameLayout(this);
        root.addView(new HudView(this, true));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(d(40), d(250), d(40), d(20));
        content.addView(heading("INPUTS"));
        inputsRow = row();
        content.addView(scroller(inputsRow));
        content.addView(heading("APPS"));
        appsRow = row();
        content.addView(scroller(appsRow));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        View focused = getCurrentFocus();
        int focusedId = focused != null ? focused.getId() : View.NO_ID;
        buildInputs();
        buildApps();
        View restore = focusedId != View.NO_ID ? findViewById(focusedId) : null;
        if (restore != null) {
            restore.requestFocus();
        } else if (inputsRow.getChildCount() > 0) {
            inputsRow.getChildAt(0).requestFocus();
        } else if (appsRow.getChildCount() > 0) {
            appsRow.getChildAt(0).requestFocus();
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

    // ---------------------------------------------------------------- rows

    private void buildInputs() {
        inputsRow.removeAllViews();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> label(a).compareToIgnoreCase(label(b)));
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.isHidden(this)) {
                    continue;
                }
                boolean connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
                Intent watch = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
                inputsRow.addView(tile(label(info), connected ? "CONNECTED" : "NO SIGNAL", null, 150, 84,
                        connected ? CYAN : 0xFF6E8A99, 1000 + inputsRow.getChildCount(), watch));
            }
        }
        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        if (settings.resolveActivity(getPackageManager()) != null) {
            inputsRow.addView(tile("SETTINGS", "TV MENU", null, 150, 84, GOLD, 1999, settings));
        }
    }

    private void buildApps() {
        appsRow.removeAllViews();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(launcherIntent(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(launcherIntent(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (getPackageName().equals(ai.packageName) && HomeActivity.class.getName().equals(ai.name)) {
                continue;
            }
            if (seen.add(ai.packageName)) {
                apps.add(ri);
            }
        }
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        int id = 2000;
        for (ResolveInfo ri : apps) {
            ActivityInfo ai = ri.activityInfo;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ai.packageName, ai.name));
            }
            Drawable art = ai.loadBanner(pm);
            if (art == null) {
                art = ai.loadIcon(pm);
            }
            appsRow.addView(tile(ri.loadLabel(pm).toString(), null, art, 200, 112, CYAN, id++, launch));
        }
    }

    private static Intent launcherIntent(String category) {
        return new Intent(Intent.ACTION_MAIN).addCategory(category);
    }

    private String label(TvInputInfo info) {
        CharSequence l = info.loadLabel(this);
        return TextUtils.isEmpty(l) ? info.getId() : l.toString();
    }

    // ---------------------------------------------------------------- views

    private View tile(String title, String caption, Drawable art, int w, int h, int accent, int id, Intent intent) {
        FrameLayout t = new FrameLayout(this);
        t.setId(id);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(frame(TILE_EDGE, 1));
        t.setPadding(d(6), d(6), d(6), d(6));

        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(art);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            t.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.CENTER);
            text.addView(text(title.toUpperCase(Locale.US), 17, WHITE, true));
            if (caption != null) {
                text.addView(text(caption, 11, accent, false));
            }
            t.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        t.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? frame(CYAN, 3) : frame(TILE_EDGE, 1));
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
        });
        t.setOnClickListener(v -> open(intent, title));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(w), d(h));
        lp.setMarginEnd(d(18));
        lp.topMargin = d(8);
        lp.bottomMargin = d(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void open(Intent intent, String title) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Cannot open " + title, Toast.LENGTH_SHORT).show();
        }
    }

    private GradientDrawable frame(int edge, int width) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(TILE_BG);
        g.setCornerRadius(d(10));
        g.setStroke(d(width), edge);
        return g;
    }

    private TextView heading(String s) {
        TextView t = text(s, 15, GOLD, true);
        t.setPadding(d(4), d(14), 0, 0);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(d(8), 0, d(8), 0);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private HorizontalScrollView scroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.addView(row);
        return h;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * px * 1.25f);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /** Design pixels (1280-wide layout) to device pixels. */
    private int d(float v) {
        return Math.round(v * px);
    }
}
