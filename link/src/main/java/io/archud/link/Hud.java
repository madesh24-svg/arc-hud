package io.archud.link;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The ARC HUD look for the phone app, matching the TV: navy gradient, cyan frames with cut corners and a bright
 * line at two corners, gold headings, monospace capitals, and the turning arc ring. Everything here is drawn in
 * code, so the app needs no image files.
 */
final class Hud {
    static final int CYAN = 0xFF50DCFF;
    static final int GOLD = 0xFFFFB93C;
    static final int OK = 0xFF78FFAA;
    static final int WARN = 0xFFFF6A4A;
    static final int DIM = 0xFF96C8E1;
    static final int WHITE = 0xFFEBFAFF;
    static final int FRAME = 0xFF2A7A9E;
    static final int INK = 0xFF02081A;
    static final int PANEL = 0xCC0A2236;

    static final int SOLID = 0;
    static final int OUTLINE = 1;
    static final int GOLDEN = 2;
    static final int DANGER = 3;

    static final int ICON_PHOTO = 0;
    static final int ICON_VIDEO = 1;
    static final int ICON_LINK = 2;
    static final int ICON_AUDIO = 3;

    private Hud() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Dark system bars, so the HUD background runs to the edges of the screen. */
    static void bars(Activity a) {
        a.getWindow().setStatusBarColor(INK);
        a.getWindow().setNavigationBarColor(INK);
    }

    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF02081A, 0xFF00182C});
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.1f);
        glow.setGradientRadius(dp(c, 340));
        glow.setColors(new int[] {0x8800648C, 0x0000648C});
        return new LayerDrawable(new Drawable[] {base, glow});
    }

    // ---------------------------------------------------------------- frames

    /** A box with two cut corners, a thin edge and a bright line along the top-left and bottom-right. */
    static final class Frame extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int fillColor;
        private final int pressedColor;
        private final float cut;
        private final float accentLen;
        private float left;
        private float top;
        private float right;
        private float bottom;
        private boolean pressed;

        Frame(Context c, float cutDp, int fillColor, int pressedColor, int edgeColor, int accentColor) {
            this.fillColor = fillColor;
            this.pressedColor = pressedColor;
            this.cut = dp(c, cutDp);
            this.accentLen = dp(c, 34);
            fill.setStyle(Paint.Style.FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(c, 1));
            edge.setColor(edgeColor);
            accent.setStyle(Paint.Style.STROKE);
            accent.setStrokeWidth(dp(c, 2));
            accent.setColor(accentColor);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float h = edge.getStrokeWidth() / 2f;
            left = b.left + h;
            top = b.top + h;
            right = b.right - h;
            bottom = b.bottom - h;
            path.reset();
            path.moveTo(left + cut, top);
            path.lineTo(right, top);
            path.lineTo(right, bottom - cut);
            path.lineTo(right - cut, bottom);
            path.lineTo(left, bottom);
            path.lineTo(left, top + cut);
            path.close();
        }

        @Override
        public void draw(Canvas c) {
            fill.setColor(pressed ? pressedColor : fillColor);
            c.drawPath(path, fill);
            c.drawPath(path, edge);
            c.drawLine(left + cut, top, left + cut + accentLen, top, accent);
            c.drawLine(right - cut - accentLen, bottom, right - cut, bottom, accent);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int s : state) {
                if (s == android.R.attr.state_pressed) {
                    now = true;
                }
            }
            if (now != pressed) {
                pressed = now;
                invalidateSelf();
                return true;
            }
            return false;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static Frame box(Context c, int fill, int edge) {
        return new Frame(c, 8, fill, fill, edge, CYAN);
    }

    // ---------------------------------------------------------------- text, buttons, fields

    static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, dp(c, 3), 0, dp(c, 3));
        return t;
    }

    /** A HUD button. With weighted true it shares a row equally with its neighbours. */
    static TextView button(Context c, String label, int style, boolean weighted, final Runnable action) {
        int text = CYAN;
        int fill = 0x2250DCFF;
        int pressed = 0x6650DCFF;
        int edge = CYAN;
        int accent = CYAN;
        if (style == SOLID) {
            text = INK;
            fill = CYAN;
            pressed = 0xFFA8EEFF;
            accent = WHITE;
        } else if (style == GOLDEN) {
            text = GOLD;
            fill = 0x22FFB93C;
            pressed = 0x66FFB93C;
            edge = GOLD;
            accent = GOLD;
        } else if (style == DANGER) {
            text = WARN;
            fill = 0x22FF6A4A;
            pressed = 0x66FF6A4A;
            edge = WARN;
            accent = WARN;
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(text);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(c, 8), dp(c, 14), dp(c, 8), dp(c, 14));
        t.setBackground(new Frame(c, 10, fill, pressed, edge, accent));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = weighted
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(weighted ? dp(c, 3) : 0, dp(c, 8), weighted ? dp(c, 3) : 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    static EditText field(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0xFF6E8A99);
        e.setTextColor(WHITE);
        e.setTextSize(14);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setInputType(inputType == 0 ? InputType.TYPE_CLASS_TEXT : inputType);
        e.setBackground(new Frame(c, 6, 0xCC02081A, 0xCC02081A, FRAME, 0x0050DCFF));
        e.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        e.setLayoutParams(lp);
        return e;
    }

    /** A framed section with a gold heading; add the content to it. */
    static LinearLayout panel(Context c, String heading) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 14));
        p.setBackground(new Frame(c, 8, PANEL, PANEL, FRAME, CYAN));
        TextView h = label(c, heading, 12, GOLD, true);
        h.setLetterSpacing(0.18f);
        p.addView(h);
        return p;
    }

    static LinearLayout.LayoutParams stack(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
        return lp;
    }

    /** A thin cyan line that fades out to the right. */
    static View rule(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {CYAN, 0x0050DCFF}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1));
        lp.setMargins(0, dp(c, 10), 0, dp(c, 12));
        v.setLayoutParams(lp);
        return v;
    }

    /** A big square-ish tile with a drawn icon, like the TV home tiles. Disabled tiles are dimmed. */
    static LinearLayout tile(Context c, int icon, String label, boolean enabled, final Runnable action) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(new Frame(c, 10, PANEL, 0xDD0F3350, FRAME, CYAN));
        t.addView(new Icon(c, icon, enabled ? CYAN : DIM), new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));
        TextView l = label(c, label, 11, WHITE, true);
        l.setLetterSpacing(0.16f);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(c, 8), 0, 0);
        t.addView(l);
        if (enabled) {
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.run();
                }
            });
        } else {
            t.setAlpha(0.45f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 96), 1f);
        lp.setMargins(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------------------------------------------------------- status line

    /** A dot and one line of text: green when all is well, gold or red otherwise. */
    static final class Status extends LinearLayout {
        private final TextView text;
        private final GradientDrawable dot = new GradientDrawable();

        Status(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(DIM);
            View d = new View(c);
            d.setBackground(dot);
            addView(d, new LinearLayout.LayoutParams(dp(c, 9), dp(c, 9)));
            text = label(c, "", 12, DIM, false);
            text.setPadding(dp(c, 9), 0, 0, 0);
            addView(text, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }

        void set(String s, int color) {
            dot.setColor(color);
            text.setTextColor(color);
            text.setText(s);
        }

        /** Shows a plain message; failures turn red. */
        void setText(String s) {
            boolean bad = s.startsWith("Could not") || s.startsWith("The TV closed") || s.startsWith("No photos")
                    || s.startsWith("ARC LINK cannot");
            set(s, bad ? WARN : (s.startsWith("On the TV") ? OK : CYAN));
        }
    }

    // ---------------------------------------------------------------- the arc ring

    /** The turning arc ring from the TV HUD; it only turns while it is on screen. */
    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float angle;
        private ValueAnimator anim;

        Ring(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 2.5f));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            anim = ValueAnimator.ofFloat(0f, 360f);
            anim.setDuration(6000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    angle = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - p.getStrokeWidth();
            p.setColor(FRAME);
            p.setAlpha(120);
            c.drawCircle(cx, cy, r, p);
            p.setAlpha(255);
            p.setColor(CYAN);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, angle, 60, false, p);
            c.drawArc(oval, angle + 180, 60, false, p);
            p.setColor(GOLD);
            float r2 = r * 0.62f;
            oval.set(cx - r2, cy - r2, cx + r2, cy + r2);
            c.drawArc(oval, -angle * 1.7f, 80, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CYAN);
            c.drawCircle(cx, cy, p.getStrokeWidth() * 1.3f, p);
            p.setStyle(Paint.Style.STROKE);
        }
    }

    // ---------------------------------------------------------------- tile icons

    /** Simple line icons (photo, video, link, audio), drawn so they need no image files or symbol fonts. */
    static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final int kind;

        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStrokeWidth(dp(c, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float m = p.getStrokeWidth();
            p.setStyle(Paint.Style.STROKE);
            if (kind == ICON_PHOTO) {
                r.set(m, h * 0.18f, w - m, h * 0.82f);
                c.drawRoundRect(r, w * 0.1f, w * 0.1f, p);
                c.drawCircle(w * 0.7f, h * 0.38f, w * 0.07f, p);
                path.reset();
                path.moveTo(m * 2, h * 0.72f);
                path.lineTo(w * 0.38f, h * 0.46f);
                path.lineTo(w * 0.55f, h * 0.62f);
                path.lineTo(w * 0.68f, h * 0.52f);
                path.lineTo(w - m * 2, h * 0.72f);
                c.drawPath(path, p);
            } else if (kind == ICON_VIDEO) {
                c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - m, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(w * 0.42f, h * 0.32f);
                path.lineTo(w * 0.70f, h * 0.5f);
                path.lineTo(w * 0.42f, h * 0.68f);
                path.close();
                c.drawPath(path, p);
            } else if (kind == ICON_LINK) {
                r.set(m, h * 0.30f, w * 0.62f, h * 0.70f);
                c.drawRoundRect(r, h * 0.2f, h * 0.2f, p);
                r.set(w * 0.38f, h * 0.30f, w - m, h * 0.70f);
                c.drawRoundRect(r, h * 0.2f, h * 0.2f, p);
            } else {
                r.set(w * 0.18f, h * 0.60f, w * 0.48f, h * 0.88f);
                p.setStyle(Paint.Style.FILL);
                c.drawOval(r, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(w * 0.48f, h * 0.74f, w * 0.48f, h * 0.14f, p);
                c.drawLine(w * 0.48f, h * 0.14f, w * 0.82f, h * 0.28f, p);
            }
        }
    }
}
