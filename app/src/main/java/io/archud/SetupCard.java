package io.archud;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** HUD-styled prompt with D-pad focusable buttons. */
final class SetupCard extends LinearLayout {
    interface Action {
        void run();
    }

    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int WHITE = 0xFFEBFAFF;
    private static final int INK = 0xFF02081A;

    private final float density;
    private final TextView heading;
    private final TextView body;
    private final LinearLayout buttons;
    private final ScrollView scroll;

    SetupCard(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int pad = dp(22);
        setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2020A18);
        bg.setStroke(dp(2), CYAN);
        bg.setCornerRadius(dp(6));
        setBackground(bg);

        heading = label(context, 20, GOLD, true);
        body = label(context, 16, WHITE, false);
        body.setPadding(0, dp(10), 0, dp(18));
        body.setLineSpacing(0, 1.2f);
        buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        scroll = new ScrollView(context);
        scroll.addView(buttons);
        addView(heading);
        addView(body);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void show(String title, String message, String[] labels, Action[] actions) {
        show(title, message, labels, actions, false);
    }

    /** vertical = one button per line, scrolling when long (used for lists). */
    void show(String title, String message, String[] labels, Action[] actions, boolean vertical) {
        buttons.setOrientation(vertical ? VERTICAL : HORIZONTAL);
        scroll.getLayoutParams().height = vertical && labels.length > 6 ? dp(300) : LayoutParams.WRAP_CONTENT;
        scroll.requestLayout();
        heading.setText(title);
        body.setText(message);
        buttons.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView b = button(labels[i]);
            final Action action = actions[i];
            b.setOnClickListener(v -> action.run());
            LayoutParams lp = new LayoutParams(vertical ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            if (vertical) {
                lp.bottomMargin = dp(8);
            } else {
                lp.setMarginEnd(dp(12));
            }
            buttons.addView(b, lp);
        }
        setVisibility(VISIBLE);
        if (buttons.getChildCount() > 0) {
            buttons.getChildAt(0).requestFocus();
        }
    }

    void hide() {
        setVisibility(GONE);
    }

    private TextView button(String text) {
        TextView b = label(getContext(), 15, CYAN, true);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);

        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x00000000);
        idle.setStroke(dp(1), CYAN);
        idle.setCornerRadius(dp(4));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(CYAN);
        focused.setCornerRadius(dp(4));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {android.R.attr.state_pressed}, focused);
        states.addState(new int[] {}, idle);
        b.setBackground(states);
        b.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {INK, INK, CYAN}));
        return b;
    }

    private static TextView label(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        setFocusable(visibility == View.VISIBLE);
    }
}
