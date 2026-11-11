package tr.com.navortal;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 24 saatlik kadran: üst 00, sağ 06, alt 12, sol 18; saat yönünde. Geçen süre soluk, kalan süre marka mavisi. */
public final class DayRingView extends View {
    private static final float SIZE_DP = 240, RADIUS_DP = 96, STROKE_DP = 16, DOT_DP = 9, DOT_BORDER_DP = 4;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int elapsedColor, accent, white;
    private float fraction;
    private float pulse; // 0..1, yalnız pulsing iken
    private ValueAnimator pulseAnimator;

    public DayRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        elapsedColor = context.getColor(R.color.ring_elapsed);
        accent = context.getColor(R.color.brand_blue);
        white = context.getColor(R.color.white);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(dp(STROKE_DP));
        label.setColor(context.getColor(R.color.muted));
        label.setTextAlign(Paint.Align.CENTER);
        label.setFontFeatureSettings("tnum");
        // 11sp; ama büyük yazı tipinde halkanın 16dp'lik boşluğuna sığması için sınırlı.
        label.setTextSize(Math.min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()), dp(14)));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Geçen gün oranı (0..1): yay ve nokta buna göre çizilir. */
    void setFraction(float f) {
        fraction = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    /** Noktanın etrafındaki yumuşak halenin 2.6 sn'lik döngüsünü başlatır/durdurur. */
    void setPulsing(boolean on) {
        if (on == (pulseAnimator != null)) return;
        if (!on) {
            pulseAnimator.cancel();
            pulseAnimator = null;
            invalidate();
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(2600);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
        pulseAnimator.start();
    }

    @Override protected void onDetachedFromWindow() {
        setPulsing(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(SIZE_DP);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(RADIUS_DP);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        float elapsedSweep = 360f * fraction;
        if (fraction > 0f) { arc.setColor(elapsedColor); canvas.drawArc(oval, -90f, elapsedSweep, false, arc); }
        if (fraction < 1f) { arc.setColor(accent); canvas.drawArc(oval, -90f + elapsedSweep, 360f - elapsedSweep, false, arc); }

        double angle = Math.toRadians(-90.0 + elapsedSweep);
        float px = cx + (float) (r * Math.cos(angle)), py = cy + (float) (r * Math.sin(angle));
        float dot = dp(DOT_DP);
        if (pulseAnimator != null && pulse < 0.7f) {
            float t = pulse / 0.7f, eased = 1f - (1f - t) * (1f - t);
            fill.setColor(accent);
            fill.setAlpha(Math.round(255 * 0.55f * (1f - eased)));
            canvas.drawCircle(px, py, dot * (1f + 1.6f * eased), fill);
        }
        fill.setColor(accent);
        canvas.drawCircle(px, py, dot, fill);
        fill.setColor(white);
        canvas.drawCircle(px, py, dot - dp(DOT_BORDER_DP), fill);

        var fm = label.getFontMetrics();
        float mid = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("00", cx, -fm.ascent, label);
        canvas.drawText("12", cx, getHeight() - fm.descent, label);
        canvas.drawText("06", getWidth() - label.measureText("06") / 2f, mid, label);
        canvas.drawText("18", label.measureText("18") / 2f, mid, label);
    }
}
