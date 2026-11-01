package io.archud;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ARC SEARCH: one section per SearchProvider. Opened by a voice search (the spoken text arrives through
 * VoiceSearchWatcher) or by the SEARCH tile (type the query). Results open in SmartTube or the browser.
 */
public class SearchActivity extends Activity {
    static final String EXTRA_QUERY = "query";
    static final String EXTRA_VOICE = "voice";
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int DIM = 0xFF96C8E1;
    private static final int WARN = 0xFFFF6A4A;
    private static final int INK = 0xFF02081A;

    /** The views of one provider's section. */
    private static final class Section {
        final SearchProvider provider;
        final TextView status;
        final LinearLayout rows;

        Section(SearchProvider provider, TextView status, LinearLayout rows) {
            this.provider = provider;
            this.status = status;
            this.rows = rows;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<SearchProvider> providers = SearchProviders.all();
    private float px;
    private EditText input;
    private LinearLayout sections;
    private int generation;
    /** True once the user has pressed a key; until then focus follows the top-most result. */
    private boolean userMoved;
    private final List<Section> built = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        px = getResources().getDisplayMetrics().widthPixels / 1280f;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(d(80), d(30), d(80), d(50));

        page.addView(text("ARC SEARCH", 30, WHITE, true));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Type here, or say it from your phone");
        input.setHintTextColor(DIM);
        input.setTextColor(WHITE);
        input.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * px * 1.25f);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, event) -> {
            String q = v.getText().toString().trim();
            if (!q.isEmpty()) {
                runSearch(q);
            }
            return true;
        });
        page.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        page.addView(sections);
        scroll.addView(page);
        setContentView(scroll);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String q = intent != null ? intent.getStringExtra(EXTRA_QUERY) : null;
        if (q != null && !q.trim().isEmpty()) {
            input.setText(q.trim());
            runSearch(q.trim());
        } else {
            input.requestFocus();
            main.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, 0);
                }
            }, 300);
        }
    }

    // ---------------------------------------------------------------- searching

    private void runSearch(final String query) {
        generation++;
        final int gen = generation;
        userMoved = false;
        built.clear();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        sections.removeAllViews();
        for (final SearchProvider provider : providers) {
            sections.addView(heading(provider.heading()));
            TextView status = text("SEARCHING ...", 14, DIM, false);
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            sections.addView(status);
            sections.addView(rows);
            final Section section = new Section(provider, status, rows);
            built.add(section);
            new Thread(() -> {
                List<SearchResult> found = null;
                String error = null;
                try {
                    found = provider.search(getApplicationContext(), query);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
                }
                final List<SearchResult> results = found;
                final String failure = error;
                main.post(() -> {
                    if (gen == generation && !isDestroyed()) {
                        fill(section, results, failure);
                    }
                });
            }, "search-" + provider.heading()).start();
        }
    }

    private void fill(Section s, List<SearchResult> results, String failure) {
        List<SearchResult> normal = new ArrayList<>();
        List<SearchResult> pinned = new ArrayList<>();
        if (results != null) {
            for (SearchResult r : results) {
                (r.pinned ? pinned : normal).add(r);
            }
        }
        if (failure != null) {
            s.status.setText("UNAVAILABLE: " + failure);
            s.status.setTextColor(WARN);
        } else if (normal.isEmpty() && pinned.isEmpty()) {
            s.status.setText("NO RESULTS");
        } else if (normal.isEmpty()) {
            s.status.setText("NO MATCHING ITEMS");
        } else {
            s.status.setVisibility(View.GONE);
        }
        s.rows.removeAllViews();
        int first = Math.min(s.provider.firstRows(), normal.size());
        for (int i = 0; i < first; i++) {
            s.rows.addView(row(normal.get(i)));
        }
        if (normal.size() > first) {
            final List<SearchResult> rest = normal.subList(first, normal.size());
            final View[] moreRef = new View[1];
            moreRef[0] = actionRow("MORE (" + rest.size() + ")", () -> {
                int at = s.rows.indexOfChild(moreRef[0]);
                s.rows.removeView(moreRef[0]);
                View firstNew = null;
                for (int i = 0; i < rest.size(); i++) {
                    View v = row(rest.get(i));
                    s.rows.addView(v, at + i);
                    if (firstNew == null) {
                        firstNew = v;
                    }
                }
                if (firstNew != null) {
                    firstNew.requestFocus();
                }
            });
            s.rows.addView(moreRef[0]);
        }
        for (SearchResult r : pinned) {
            s.rows.addView(row(r));
        }
        if (!userMoved) {
            focusTop();
        }
    }

    /** Focuses the first row of the first section that has any, in section order (not arrival order). */
    private void focusTop() {
        for (Section sec : built) {
            if (sec.rows.getChildCount() > 0) {
                sec.rows.getChildAt(0).requestFocus();
                return;
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            userMoved = true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void open(SearchResult r) {
        try {
            startActivity(r.intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Cannot open: " + r.title, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- views

    private View row(final SearchResult r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(10), d(8), d(16), d(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(rowBackground());
        if (r.thumbUrl != null) {
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF0A2236);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(144), d(81));
            lp.setMarginEnd(d(16));
            row.addView(thumb, lp);
            Thumbs.load(r.thumbUrl, thumb, d(144));
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(r.title, 17, WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        final TextView sub = text(r.subtitle, 13, CYAN, false);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> open(r));
        row.setOnFocusChangeListener((v, focused) -> {
            title.setTextColor(focused ? INK : WHITE);
            sub.setTextColor(focused ? INK : CYAN);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = d(5);
        row.setLayoutParams(lp);
        return row;
    }

    private View actionRow(String label, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(d(16), d(12), d(16), d(12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(rowBackground());
        final TextView t = text(label, 16, GOLD, true);
        row.addView(t);
        row.setOnClickListener(v -> action.run());
        row.setOnFocusChangeListener((v, focused) -> t.setTextColor(focused ? INK : GOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = d(5);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView heading(String s) {
        TextView t = text(s, 16, GOLD, true);
        t.setPadding(0, d(22), 0, d(6));
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

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * px * 1.25f);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private int d(float v) {
        return Math.round(v * px);
    }
}
