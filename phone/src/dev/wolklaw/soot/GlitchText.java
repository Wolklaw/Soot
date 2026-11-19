package dev.wolklaw.soot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * A heading with the house fault: the magenta and cyan channels sit 2px out of register, and on a
 * burst they slip further apart in four hard steps. Bursts 450 ms after it appears, then every 7-12 s,
 * and whenever burst() is called for an event.
 */
final class GlitchText extends View {
    private static final int CH_A = 0xFFFF2E88, CH_B = 0xFF22D8FF, TEXT = 0xFFD4F1EC;
    private static final float[][] STEPS = { { 7, -3 }, { -6, 4 }, { 5, -2 }, { -8, 2 } };   // channel offset, text nudge (dp)
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float dp;
    private String text = "";
    private int step = -1;
    private final Runnable loop = new Runnable() {
        @Override public void run() { burst(); handler.postDelayed(this, 7000 + (long) (Math.random() * 5000)); }
    };

    GlitchText(Context c, float sizeSp) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(sizeSp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    void setText(String t) { text = t; requestLayout(); invalidate(); }

    void burst() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;      // respects "remove animations"
        for (int i = 0; i <= STEPS.length; i++) {
            final int s = i < STEPS.length ? i : -1;
            handler.postDelayed(() -> { step = s; invalidate(); }, i * 85L);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(this::burst, 450);
        handler.postDelayed(loop, 7000 + (long) (Math.random() * 5000));
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int w, int h) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        setMeasuredDimension(resolveSize((int) (paint.measureText(text) + 20 * dp), w), (int) (fm.descent - fm.ascent + 4 * dp));
    }

    @Override protected void onDraw(Canvas canvas) {
        float y = -paint.getFontMetrics().ascent + 2 * dp;
        float off = (step < 0 ? 2 : STEPS[step][0]) * dp, nudge = (step < 0 ? 0 : STEPS[step][1]) * dp, x = 8 * dp + nudge;
        paint.setColor(CH_A); canvas.drawText(text, x + off, y, paint);
        paint.setColor(CH_B); canvas.drawText(text, x - off, y, paint);
        paint.setColor(TEXT); canvas.drawText(text, x, y, paint);
    }
}
