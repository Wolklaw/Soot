package dev.wolklaw.soot;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The house look for the browser screens: terminal palette, mono type, sharp 3dp corners, and the channel-slip fault. */
final class Ui {
    static final int BG = 0xFF04090B, PANEL = 0xFF0A1517, PANEL2 = 0xFF0E1F22, LINE = 0xFF143A3D, LINE2 = 0xFF1F6A66,
            TEXT = 0xFFD4F1EC, MUTED = 0xFF6F9F98, DIM = 0xFF3B6460, ACCENT = 0xFF3DE8C8, ON_ACCENT = 0xFF02130F, PINK = 0xFFFF4D8D,
            CH_A = 0xFFFF2E88, CH_B = 0xFF22D8FF;

    private Ui() { }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static GradientDrawable box(Context c, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 3));
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /** Small bold caps label, the micro-label of the house style. */
    static TextView label(Context c, String s, int color) {
        TextView t = text(c, s, 10.5f, color, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    static LinearLayout.LayoutParams fill(Context c, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, top);
        return p;
    }

    // ---- the fault ----

    /** Glitch effects can be switched off in the menu. The static 2px channel offset on headings stays either way. */
    static boolean glitchOn(Context c) {
        return c.getSharedPreferences("soot-ui", Context.MODE_PRIVATE).getBoolean("glitch", true) && ValueAnimator.areAnimatorsEnabled();
    }

    static void setGlitch(Context c, boolean on) {
        c.getSharedPreferences("soot-ui", Context.MODE_PRIVATE).edit().putBoolean("glitch", on).apply();
    }

    /**
     * A panel that tears on events: for four hard steps its content jumps sideways and a magenta and a
     * cyan slice cut across it. Call burst() when something happens (page error, tab change, fill, clear).
     */
    static final class GlitchBox extends FrameLayout {
        private static final float[][] STEPS = { { 6, 0.18f, 0.22f }, { -5, 0.55f, 0.16f }, { 4, 0.30f, 0.30f }, { -7, 0.70f, 0.14f } };   // nudge dp, slice top, slice height
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint();
        private int step = -1;

        GlitchBox(Context c) { super(c); setWillNotDraw(false); }

        void burst() {
            if (!glitchOn(getContext())) return;
            handler.removeCallbacksAndMessages(null);
            for (int i = 0; i <= STEPS.length; i++) {
                final int s = i < STEPS.length ? i : -1;
                handler.postDelayed(() -> {
                    step = s;
                    for (int k = 0; k < getChildCount(); k++) getChildAt(k).setTranslationX(s < 0 ? 0 : dp(getContext(), STEPS[s][0]));
                    invalidate();
                }, i * 85L);
            }
        }

        @Override protected void onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (step < 0) return;
            float w = getWidth(), h = getHeight(), top = h * STEPS[step][1], height = Math.max(dp(getContext(), 2), h * STEPS[step][2]);
            float off = dp(getContext(), Math.abs(STEPS[step][0]));
            paint.setColor(CH_A); paint.setAlpha(150); canvas.drawRect(off, top, w, top + height / 2, paint);
            paint.setColor(CH_B); paint.setAlpha(150); canvas.drawRect(0, top + height / 2, w - off, top + height, paint);
        }
    }

    // ---- sheets: the menu, the tab list and every prompt ----

    /** A panel that rises from the bottom of the screen with a caps title; add rows to body. */
    static final class Sheet {
        final Dialog dialog;
        final LinearLayout body;
        final GlitchText title;

        Sheet(Activity a, String heading) {
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout frame = new LinearLayout(a);
            frame.setOrientation(LinearLayout.VERTICAL);
            frame.setBackground(box(a, PANEL, LINE2));
            frame.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 16));
            title = new GlitchText(a, 20);
            title.setText(heading);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = -dp(a, 8);
            frame.addView(title, tp);
            ScrollView scroll = new ScrollView(a);
            body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);
            frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(frame);
            Window w = dialog.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getDecorView().setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 8));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if ((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        Sheet note(String s) {
            body.addView(text(dialog.getContext(), s, 13, MUTED, false), fill(dialog.getContext(), 8));
            return this;
        }

        /** A row with a caps label and an optional value on the right; closes the sheet when tapped. */
        TextView row(String name, String value, int valueColor, Runnable on) {
            Context c = dialog.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 13), dp(c, 14), dp(c, 13), dp(c, 14));
            row.setBackground(box(c, PANEL2, LINE));
            TextView n = label(c, name, on == null ? MUTED : TEXT);
            n.setTextSize(12);
            row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView v = label(c, value == null ? "" : value, valueColor);
            row.addView(v);
            if (on != null) row.setOnClickListener(x -> { dialog.dismiss(); on.run(); });
            body.addView(row, fill(c, 6));
            return v;
        }

        TextView button(String s, boolean primary, Runnable on) {
            Context c = dialog.getContext();
            TextView b = text(c, s, 12, primary ? ON_ACCENT : TEXT, true);
            b.setLetterSpacing(0.08f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
            b.setBackground(box(c, primary ? ACCENT : PANEL, primary ? ACCENT : LINE2));
            b.setOnClickListener(x -> { dialog.dismiss(); on.run(); });
            body.addView(b, fill(c, 10));
            return b;
        }

        void show() { dialog.show(); }
    }
}
