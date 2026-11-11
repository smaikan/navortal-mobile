package tr.com.navortal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

/** Simgeyi metin satırının dikey ortasına koyar ({@code ImageSpan.ALIGN_CENTER} API 29+, minSdk 26). */
final class CenteredImageSpan extends ReplacementSpan {
    private final Drawable drawable;

    CenteredImageSpan(Drawable drawable) { this.drawable = drawable; }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return drawable.getBounds().width();
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        var metrics = paint.getFontMetrics();
        float center = y + (metrics.ascent + metrics.descent) / 2f;
        canvas.save();
        canvas.translate(x, center - drawable.getBounds().height() / 2f);
        drawable.draw(canvas);
        canvas.restore();
    }
}
