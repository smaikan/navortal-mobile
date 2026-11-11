package tr.com.navortal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Görev sürelerini yan yana renkli parçalar olarak gösteren 8dp'lik süs çubuğu. */
public final class PlanBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float[] fractions = new float[0];
    private int[] colors = new int[0];

    public PlanBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** fractions: her parçanın çubuk genişliğine oranı (0..1). */
    void setSegments(float[] fractions, int[] colors) {
        this.fractions = fractions;
        this.colors = colors;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(getContext().getColor(R.color.bar_track));
        canvas.drawRect(0, 0, w, h, paint);
        float x = 0;
        for (int i = 0; i < fractions.length; i++) {
            float width = Math.max(0, fractions[i]) * w;
            if (width <= 0) continue;
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, x + width, h, paint);
            x += width;
        }
        canvas.restore();
    }
}
