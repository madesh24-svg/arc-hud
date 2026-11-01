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
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Launcher home: live HUD background with Inputs and Apps rows on top.
 * MENU on a tile (or long-press OK where the remote supports it) hides it or pins it to the front;
 * hidden tiles are listed at the end of each row. MENU elsewhere opens ARC SETTINGS.
 */
public class HomeActivity extends Activity {
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int MUTED = 0xFF6E8A99;
    private static final int TILE_BG = 0xCC0A2236;
    private static final int TILE_EDGE = 0xFF2A7A9E;

    /** A launchable tile: key is stable across rebuilds ("input:<id>" or "app:<package>"). */
    private static final class Item {
        final String key;
        final String title;
        final String caption;
        final int captionColor;
        final Drawable art;
        final Intent intent;

        Item(String key, String title, String caption, int captionColor, Drawable art, Intent intent) {
            this.key = key;
            this.title = title;
            this.caption = caption;
            this.captionColor = captionColor;
            this.art = art;
            this.intent = intent;
        }
    }

    private float px;
    private HudView hud;
    private SetupCard card;
    private FlowLayout inputsRow;
    private FlowLayout appsRow;
    private List<Item> hiddenInputs = new ArrayList<>();
    private List<Item> hiddenApps = new ArrayList<>();
    private final Map<String, Item> editableItems = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;

        FrameLayout root = new FrameLayout(this);
        hud = new HudView(this, true);
        root.addView(hud);

        // Tiles wrap onto as many lines as fit; the area only scrolls when there are more than fit.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(d(40), d(166), d(40), d(30));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.addView(heading("INPUTS"));
        inputsRow = row();
        content.addView(inputsRow);
        content.addView(heading("APPS"));
        appsRow = row();
        content.addView(appsRow);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        card = new SetupCard(this);
        root.addView(card, new FrameLayout.LayoutParams(d(620), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hud.reload();
        rebuild(null);
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
        if (keyCode == KeyEvent.KEYCODE_MENU && !card.isShowing()) {
            // IR remotes send one short press per tap, so MENU (not long-press) is the reliable way in.
            View focused = getCurrentFocus();
            Item it = focused != null ? editableItems.get(String.valueOf(focused.getTag())) : null;
            if (it != null) {
                tileMenu(it);
            } else {
                openSettings();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (card.isShowing()) {
            closeCard();
            return;
        }
        if (isDefaultHome()) {
            return; // the home screen has nowhere to go back to
        }
        super.onBackPressed();
    }

    /** HOME pressed while already here: close any popup and return to the first tile. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            card.hide();
            rebuild("");
        }
    }

    private boolean isDefaultHome() {
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null && getPackageName().equals(home.activityInfo.packageName);
    }

    // ---------------------------------------------------------------- rows

    private void rebuild(String focusKey) {
        View focused = getCurrentFocus();
        Object keep = focusKey != null ? focusKey : (focused != null ? focused.getTag() : null);
        Set<String> hidden = Prefs.hidden(this);
        List<String> favs = Prefs.favs(this);
        editableItems.clear();

        inputsRow.removeAllViews();
        hiddenInputs = new ArrayList<>();
        for (Item it : order(inputs(), favs)) {
            if (hidden.contains(it.key)) {
                hiddenInputs.add(it);
            } else {
                inputsRow.addView(tile(it, 150, 84, true));
            }
        }
        Intent tvSettings = new Intent(Settings.ACTION_SETTINGS);
        if (tvSettings.resolveActivity(getPackageManager()) != null) {
            inputsRow.addView(tile(new Item("fixed:tv-settings", "TV SETTINGS", "TV MENU", GOLD, null, tvSettings), 150, 84, false));
        }
        inputsRow.addView(tile(new Item("fixed:search", "SEARCH", "TYPE OR SPEAK", CYAN, null,
                new Intent(this, SearchActivity.class)), 150, 84, false));
        inputsRow.addView(tile(new Item("fixed:full-hud", "FULL HUD", "DISPLAY MODE", CYAN, null,
                new Intent(this, HudActivity.class)), 150, 84, false));
        inputsRow.addView(tile(new Item("fixed:devices", "DEVICES", "ON NETWORK", CYAN, null,
                new Intent(this, DevicesActivity.class)), 150, 84, false));
        inputsRow.addView(tile(new Item("fixed:arc-settings", "ARC SETTINGS", "THIS APP", GOLD, null,
                new Intent(this, SettingsActivity.class)), 150, 84, false));
        if (!hiddenInputs.isEmpty()) {
            inputsRow.addView(hiddenTile(hiddenInputs, "fixed:hidden-inputs"));
        }

        appsRow.removeAllViews();
        hiddenApps = new ArrayList<>();
        for (Item it : order(apps(), favs)) {
            if (hidden.contains(it.key)) {
                hiddenApps.add(it);
            } else {
                appsRow.addView(tile(it, 200, 112, true));
            }
        }
        if (!hiddenApps.isEmpty()) {
            appsRow.addView(hiddenTile(hiddenApps, "fixed:hidden-apps"));
        }

        View restore = keep != null ? findTile(keep) : null;
        if (restore == null) {
            restore = inputsRow.getChildCount() > 0 ? inputsRow.getChildAt(0)
                    : (appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : null);
        }
        if (restore != null) {
            restore.requestFocus();
        }
    }

    private View findTile(Object tag) {
        for (FlowLayout r : new FlowLayout[] {inputsRow, appsRow}) {
            for (int i = 0; i < r.getChildCount(); i++) {
                if (tag.equals(r.getChildAt(i).getTag())) {
                    return r.getChildAt(i);
                }
            }
        }
        return null;
    }

    private static List<Item> order(List<Item> items, List<String> favs) {
        Collections.sort(items, (a, b) -> {
            int fa = favs.indexOf(a.key);
            int fb = favs.indexOf(b.key);
            if (fa >= 0 && fb >= 0) {
                return Integer.compare(fa, fb);
            }
            if (fa >= 0 || fb >= 0) {
                return fa >= 0 ? -1 : 1;
            }
            return a.title.compareToIgnoreCase(b.title);
        });
        return items;
    }

    private List<Item> inputs() {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) {
            return out;
        }
        List<TvInputInfo> list = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isPassthroughInput() && !info.isHidden(this)) {
                list.add(info);
            }
        }
        // Sorted by id so duplicate names ("AV", "AV") are numbered the same way as in ARC SETTINGS.
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            CharSequence l = list.get(i).loadLabel(this);
            names[i] = TextUtils.isEmpty(l) ? list.get(i).getId() : l.toString();
        }
        names = TileNames.numberDuplicates(names);
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            boolean connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            Intent watch = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
            out.add(new Item("input:" + info.getId(), names[i], connected ? "CONNECTED" : "NO SIGNAL",
                    connected ? CYAN : MUTED, null, watch));
        }
        return out;
    }

    private List<Item> apps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(launcherIntent(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(launcherIntent(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (getPackageName().equals(ai.packageName) && !HudActivity.class.getName().equals(ai.name)) {
                continue;
            }
            if (!seen.add(ai.packageName + "/" + (getPackageName().equals(ai.packageName) ? ai.name : ""))) {
                continue;
            }
            Intent launch = getPackageName().equals(ai.packageName) ? null : pm.getLeanbackLaunchIntentForPackage(ai.packageName);
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ai.packageName, ai.name));
            }
            Drawable art = ai.loadBanner(pm);
            if (art == null) {
                art = ai.loadIcon(pm);
            }
            out.add(new Item("app:" + ai.packageName, ri.loadLabel(pm).toString(), null, CYAN, art, launch));
        }
        return out;
    }

    private static Intent launcherIntent(String category) {
        return new Intent(Intent.ACTION_MAIN).addCategory(category);
    }

    // ---------------------------------------------------------------- tile actions

    private void tileMenu(Item it) {
        List<String> favs = Prefs.favs(this);
        boolean pinned = favs.contains(it.key);
        card.show(it.title.toUpperCase(Locale.US), "Choose an action for this tile.",
                new String[] {"HIDE", pinned ? "UNPIN" : "PIN TO FRONT", "ARC SETTINGS", "CANCEL"},
                new SetupCard.Action[] {
                    () -> {
                        Set<String> hidden = Prefs.hidden(this);
                        hidden.add(it.key);
                        Prefs.setHidden(this, hidden);
                        card.hide();
                        rebuild(null);
                    },
                    () -> {
                        List<String> f = Prefs.favs(this);
                        if (pinned) {
                            f.remove(it.key);
                        } else {
                            f.remove(it.key);
                            f.add(it.key);
                        }
                        Prefs.setFavs(this, f);
                        card.hide();
                        rebuild(it.key);
                    },
                    () -> {
                        card.hide();
                        openSettings();
                    },
                    this::closeCard});
    }

    private void showHidden(List<Item> items) {
        int n = items.size();
        String[] labels = new String[n + 2];
        SetupCard.Action[] actions = new SetupCard.Action[n + 2];
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            labels[i] = "SHOW  " + it.title.toUpperCase(Locale.US);
            actions[i] = () -> unhide(Collections.singletonList(it.key), it.key);
        }
        List<String> all = new ArrayList<>();
        for (Item it : items) {
            all.add(it.key);
        }
        labels[n] = "SHOW ALL";
        actions[n] = () -> unhide(all, null);
        labels[n + 1] = "CANCEL";
        actions[n + 1] = this::closeCard;
        card.show("HIDDEN TILES", "Pick a tile to show it again.", labels, actions, true);
    }

    private void unhide(List<String> keys, String focusKey) {
        Set<String> hidden = Prefs.hidden(this);
        hidden.removeAll(keys);
        Prefs.setHidden(this, hidden);
        card.hide();
        rebuild(focusKey);
    }

    private void closeCard() {
        card.hide();
        rebuild(null);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void open(Intent intent, String title) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Cannot open " + title, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- views

    private View hiddenTile(List<Item> items, String key) {
        Item it = new Item(key, "HIDDEN (" + items.size() + ")", "SHOW AGAIN", GOLD, null, null);
        View t = tile(it, 150, 84, false);
        t.setOnClickListener(v -> showHidden(items));
        return t;
    }

    private View tile(Item it, int w, int h, boolean editable) {
        FrameLayout t = new FrameLayout(this);
        t.setTag(it.key);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(frame(TILE_EDGE, 1));
        t.setPadding(d(6), d(6), d(6), d(6));

        if (it.art != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(it.art);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            t.addView(iv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            TextView name = text(it.title, 12, WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setBackgroundColor(0xCC020A18);
            name.setPadding(d(6), d(2), d(6), d(2));
            t.addView(name, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        } else {
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setGravity(Gravity.CENTER);
            text.addView(text(it.title.toUpperCase(Locale.US), 16, WHITE, true));
            if (it.caption != null) {
                text.addView(text(it.caption, 11, it.captionColor, false));
            }
            t.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        t.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(focused ? frame(CYAN, 3) : frame(TILE_EDGE, 1));
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
        });
        if (it.intent != null) {
            t.setOnClickListener(v -> open(it.intent, it.title));
        }
        if (editable) {
            editableItems.put(it.key, it);
            t.setOnLongClickListener(v -> {
                tileMenu(it);
                return true;
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(w), d(h));
        lp.setMarginEnd(d(18));
        lp.topMargin = d(8);
        lp.bottomMargin = d(8);
        t.setLayoutParams(lp);
        return t;
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
        t.setGravity(Gravity.START);
        t.setPadding(d(10), d(14), 0, 0);
        return t;
    }

    private FlowLayout row() {
        FlowLayout r = new FlowLayout(this);
        r.setPadding(d(8), 0, d(8), 0);
        return r;
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
