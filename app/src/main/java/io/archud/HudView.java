package io.archud;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Animated HUD. Static layers are baked once into bitmaps; each frame only rotates them and draws text. */
public class HudView extends View {
    private static final float DW = 1280f;
    private static final float DH = 720f;
    private static final float CX = 640f;
    private static final float CY = 290f;
    private static final int CYAN = 0xFF50DCFF;
    private static final int GOLD = 0xFFFFB93C;
    private static final int OK = 0xFF78FFAA;
    private static final int DIM = 0xFF96C8E1;
    private static final int WARN = 0xFFFF6A4A;
    private static final int FRAME = 0xFF2A7A9E;
    private static final int BAR_OFF = 0xFF14323F;
    private static final int WHITE = 0xFFEBFAFF;
    private static final long FRAME_MS = 50;
    private static final long POLL_MS = 2000;

    private interface RingPainter {
        void paint(Canvas c, Paint p, boolean glow);
    }

    private final Stats stats;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE dd MMM yyyy", Locale.US);
    private final long start = SystemClock.uptimeMillis();
    private final String title;
    private final String subtitle;
    private final String salutation;

    private Bitmap background;
    private Bitmap ringOuter;
    private Bitmap ringMid;
    private Bitmap ringGold;
    private RadialGradient coreShader;
    private float scale = 1f;
    private float offX;
    private float offY;
    private boolean running;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            stats.refresh();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public HudView(Context context) {
        super(context);
        SharedPreferences prefs = context.getSharedPreferences(HudActivity.PREFS, Context.MODE_PRIVATE);
        title = prefs.getString("title", "A.R.C.  H.U.D.");
        subtitle = prefs.getString("subtitle", "ADAPTIVE RESPONSE CONSOLE");
        salutation = prefs.getString("salutation", "");
        stats = new Stats(context.getApplicationContext());
        setKeepScreenOn(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        handler.post(poll);
        handler.post(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) {
            return;
        }
        scale = Math.min(w / DW, h / DH);
        offX = (w - DW * scale) / 2f;
        offY = (h - DH * scale) / 2f;
        background = bakeBackground(w, h);
        ringOuter = bakeRing(250f, this::paintOuter);
        ringMid = bakeRing(190f, this::paintMid);
        ringGold = bakeRing(275f, this::paintGold);
        coreShader = new RadialGradient(0, 0, 120,
                new int[] {0xFFE6FAFF, 0xFF3CC8FF, 0x0000A0DC}, new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
    }

    // ---------------------------------------------------------------- baking

    private Bitmap bakeBackground(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF02081A, 0xFF00182C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        c.translate(offX, offY);
        c.scale(scale, scale);
        p.setShader(new RadialGradient(CX, CY, 620, 0x9900648C, 0x0000648C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, DW, DH, p);
        p.setShader(null);
        c.save();
        c.translate(CX, CY);
        paintInner(c, p, true);
        paintInner(c, p, false);
        c.restore();
        p.setShader(new RadialGradient(CX, DH / 2f, 820, new int[] {0x00000000, 0x00000000, 0xB0000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, DW, DH, p);
        return b;
    }

    private Bitmap bakeRing(float radius, RingPainter painter) {
        int size = (int) Math.ceil(2 * (radius + 24) * scale);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(size / 2f, size / 2f);
        c.scale(scale, scale);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        painter.paint(c, p, true);
        painter.paint(c, p, false);
        return b;
    }

    private static void stroke(Paint p, int color, float width, boolean glow) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(color);
        if (glow) {
            p.setMaskFilter(new BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL));
            p.setAlpha(170);
            p.setStrokeWidth(width * 2.2f);
        } else {
            p.setMaskFilter(null);
            p.setStrokeWidth(width);
        }
    }

    private static RectF oval(float r) {
        return new RectF(-r, -r, r, r);
    }

    private void paintInner(Canvas c, Paint p, boolean glow) {
        stroke(p, CYAN, 6f, glow);
        c.drawCircle(0, 0, 136, p);
        stroke(p, CYAN, 2f, glow);
        c.drawCircle(0, 0, 92, p);
        stroke(p, CYAN, 3f, glow);
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(k * 60 + 15);
            c.drawLine((float) (100 * Math.cos(a)), (float) (100 * Math.sin(a)),
                    (float) (128 * Math.cos(a)), (float) (128 * Math.sin(a)), p);
        }
    }

    private void paintOuter(Canvas c, Paint p, boolean glow) {
        stroke(p, CYAN, 2f, glow);
        c.drawCircle(0, 0, 232, p);
        for (int i = 0; i < 180; i++) {
            double a = Math.toRadians(i * 2);
            float len = i % 15 == 0 ? 18 : (i % 5 == 0 ? 10 : 5);
            c.drawLine((float) (232 * Math.cos(a)), (float) (232 * Math.sin(a)),
                    (float) ((232 - len) * Math.cos(a)), (float) ((232 - len) * Math.sin(a)), p);
        }
        stroke(p, CYAN, 5f, glow);
        c.drawArc(oval(204), 200, 140, false, p);
        c.drawArc(oval(204), 20, 50, false, p);
    }

    private void paintMid(Canvas c, Paint p, boolean glow) {
        stroke(p, CYAN, 3f, glow);
        c.drawCircle(0, 0, 180, p);
        stroke(p, CYAN, 10f, glow);
        for (int a = 0; a < 360; a += 30) {
            c.drawArc(oval(159), a, 18, false, p);
        }
    }

    private void paintGold(Canvas c, Paint p, boolean glow) {
        stroke(p, GOLD, 4f, glow);
        c.drawArc(oval(265), 130, 100, false, p);
        c.drawArc(oval(265), 310, 40, false, p);
        stroke(p, GOLD, 3f, glow);
        c.drawArc(oval(116), 90, 70, false, p);
    }

    // ---------------------------------------------------------------- frame

    @Override
    protected void onDraw(Canvas canvas) {
        if (background == null) {
            return;
        }
        canvas.drawBitmap(background, 0, 0, null);
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float cx = offX + CX * scale;
        float cy = offY + CY * scale;
        spin(canvas, ringOuter, cx, cy, t * 4f);
        spin(canvas, ringMid, cx, cy, -t * 9f);
        spin(canvas, ringGold, cx, cy, t * 2.5f);

        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);
        drawCore(canvas, t);
        drawPanels(canvas);
        drawTitle(canvas);
        drawClock(canvas);
        drawTicker(canvas, t);
        canvas.restore();
    }

    private void spin(Canvas c, Bitmap b, float cx, float cy, float degrees) {
        c.save();
        c.rotate(degrees % 360f, cx, cy);
        c.drawBitmap(b, cx - b.getWidth() / 2f, cy - b.getHeight() / 2f, bitmapPaint);
        c.restore();
    }

    private void drawCore(Canvas c, float t) {
        float pulse = 0.72f + 0.28f * (float) Math.sin(t * 2.2);
        c.save();
        c.translate(CX, CY);
        paint.setStyle(Paint.Style.FILL);
        paint.setMaskFilter(null);
        paint.setShader(coreShader);
        paint.setAlpha((int) (255 * pulse));
        c.drawCircle(0, 0, 120, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        stroke(paint, CYAN, 2f, false);
        paint.setAlpha(110);
        c.drawArc(oval(112), (t * 120f) % 360f, 60, false, paint);
        paint.setAlpha(255);
        c.restore();
    }

    private void drawPanels(Canvas c) {
        Stats s = stats;
        float lx = 24;
        float rx = 966;
        float pw = 290;

        panel(c, lx, 110, pw, 214, "// SYSTEM DIAGNOSTICS");
        row(c, lx, 110, pw, 0, "HOST", fit(s.host, 17), CYAN);
        row(c, lx, 110, pw, 1, "CHIPSET", fit(s.chipset, 17), CYAN);
        row(c, lx, 110, pw, 2, "OS", fit(s.os, 21), CYAN);
        row(c, lx, 110, pw, 3, "DISPLAY", getWidth() + "x" + getHeight() + " @"
                + getResources().getDisplayMetrics().densityDpi + "dpi", CYAN);
        row(c, lx, 110, pw, 4, "UPTIME", s.uptime, CYAN);
        if (s.thermal != null) {
            row(c, lx, 110, pw, 5, "THERMAL", s.thermal, CYAN);
        } else {
            row(c, lx, 110, pw, 5, "STATUS", s.healthy ? "NOMINAL" : "DEGRADED", s.healthy ? OK : WARN);
        }

        panel(c, lx, 344, pw, 214, "// NETWORK");
        row(c, lx, 344, pw, 0, "IP", s.ip, CYAN);
        row(c, lx, 344, pw, 1, "MAC", s.mac, CYAN);
        row(c, lx, 344, pw, 2, "SSID", fit(s.ssid, 21), s.wifiUp ? CYAN : WARN);
        row(c, lx, 344, pw, 3, "SIGNAL", s.signal < 0 ? "--" : s.signal + " % " + s.band, signalColor(s.signal));
        row(c, lx, 344, pw, 4, "LINK", s.linkMbps < 0 ? "--" : s.linkMbps + " MBPS", CYAN);
        row(c, lx, 344, pw, 5, "DNS", fit(s.dns, 22), "STANDARD".equals(s.dns) ? GOLD : OK);

        panel(c, rx, 110, pw, 236, "// LOAD");
        bar(c, rx, 110, pw, 0, "CPU", s.cpu, s.cpuText);
        bar(c, rx, 110, pw, 1, "RAM", s.ram, s.ramText);
        bar(c, rx, 110, pw, 2, "STORAGE", s.storage, s.storageText);
        bar(c, rx, 110, pw, 3, "DOWNLINK", s.rxFrac, s.rxText);
        bar(c, rx, 110, pw, 4, "UPLINK", s.txFrac, s.txText);

        panel(c, rx, 366, pw, 192, "// LINKS");
        row(c, rx, 366, pw, 0, "IR REMOTE", "NEC  READY", OK);
        row(c, rx, 366, pw, 1, "WI-FI REMOTE", s.remoteApp && s.wifiUp ? "ONLINE" : "OFFLINE", s.remoteApp && s.wifiUp ? OK : WARN);
        row(c, rx, 366, pw, 2, "CAST", s.castApp ? "READY" : "ABSENT", s.castApp ? OK : DIM);
        row(c, rx, 366, pw, 3, "MIRROR", s.mirrorApp ? "STANDBY" : "ABSENT", s.mirrorApp ? GOLD : DIM);
        row(c, rx, 366, pw, 4, "NETWORK", s.wifiUp ? "CONNECTED" : "DOWN", s.wifiUp ? OK : WARN);
    }

    private static int signalColor(int signal) {
        if (signal < 0) {
            return WARN;
        }
        return signal >= 60 ? OK : (signal >= 35 ? GOLD : WARN);
    }

    private void panel(Canvas c, float x, float y, float w, float h, String head) {
        paint.setMaskFilter(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55020A18);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(FRAME);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setStrokeWidth(4f);
        paint.setColor(GOLD);
        c.drawLine(x, y, x + 40, y, paint);
        c.drawLine(x + w - 40, y + h, x + w, y + h, paint);
        label(c, head, x + 14, y + 26, GOLD, 16, monoBold, Paint.Align.LEFT);
    }

    private void row(Canvas c, float x, float y, float w, int i, String key, String value, int color) {
        float yy = y + 56 + i * 26;
        label(c, key, x + 14, yy, DIM, 15, mono, Paint.Align.LEFT);
        label(c, value, x + w - 14, yy, color, 15, mono, Paint.Align.RIGHT);
    }

    private void bar(Canvas c, float x, float y, float w, int i, String key, float frac, String value) {
        float yy = y + 54 + i * 36;
        label(c, key, x + 14, yy, DIM, 14, mono, Paint.Align.LEFT);
        label(c, value, x + w - 14, yy, frac < 0 ? DIM : CYAN, 14, mono, Paint.Align.RIGHT);
        int segments = 20;
        float sw = (w - 28) / segments;
        int lit = frac < 0 ? 0 : Math.round(frac * segments);
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < segments; k++) {
            int color = BAR_OFF;
            if (k < lit) {
                color = frac > 0.85f ? WARN : (k >= segments * 0.7f ? GOLD : CYAN);
            }
            paint.setColor(color);
            float bx = x + 14 + k * sw;
            c.drawRect(bx, yy + 7, bx + sw - 3, yy + 15, paint);
        }
    }

    private void drawTitle(Canvas c) {
        label(c, title, CX, 600, WHITE, 58, condensed, Paint.Align.CENTER);
        label(c, subtitle, CX, 628, CYAN, 18, Typeface.SANS_SERIF, Paint.Align.CENTER);
    }

    private void drawClock(Canvas c) {
        Date now = new Date();
        label(c, timeFormat.format(now), 1256, 62, WHITE, 46, condensed, Paint.Align.RIGHT);
        label(c, dateFormat.format(now).toUpperCase(Locale.US), 1256, 88, CYAN, 15, mono, Paint.Align.RIGHT);
    }

    private void drawTicker(Canvas c, float t) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(FRAME);
        c.drawLine(24, 652, 1256, 652, paint);
        Stats s = stats;
        String status = s.healthy ? "ALL SYSTEMS ONLINE" : "ATTENTION: CHECK NETWORK / MEMORY";
        String msg = greeting() + "  ::  " + status + "  ::  DOWNLINK " + s.rxText + "  ::  UPLINK " + s.txText
                + "  ::  SIGNAL " + (s.signal < 0 ? "--" : s.signal + "%") + "  ::  UPTIME " + s.uptime
                + "  ::  STANDING BY";
        text.setTextSize(16);
        text.setTypeface(mono);
        float width = text.measureText(msg);
        float travel = width + 1232f;
        float x = 1256f - ((t * 70f) % travel);
        c.save();
        c.clipRect(24, 656, 1256, 700);
        label(c, msg, x, 684, CYAN, 16, mono, Paint.Align.LEFT);
        c.restore();
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 12 ? "GOOD MORNING" : (h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        return salutation.isEmpty() ? g : g + ", " + salutation.toUpperCase(Locale.US);
    }

    private void label(Canvas c, String s, float x, float y, int color, float size, Typeface tf, Paint.Align align) {
        text.setColor(color);
        text.setTextSize(size);
        text.setTypeface(tf);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    private static String fit(String s, int max) {
        if (s == null) {
            return "--";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
