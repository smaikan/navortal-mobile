package tr.com.navortal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Çocukları yan yana sığmazsa (büyük yazı tipi, dar ekran) alt alta dizen satır.
 * {@code tag="sep"} taşıyan ayraç görünümleri alt alta durumunda gizlenir.
 */
public final class InlineRow extends LinearLayout {
    public InlineRow(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int needed = 0;
            for (int i = 0; i < getChildCount(); i++) {
                var child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                var lp = (MarginLayoutParams) child.getLayoutParams();
                needed += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            }
            int wanted = needed > available ? VERTICAL : HORIZONTAL;
            if (getOrientation() != wanted) {
                setOrientation(wanted);
                for (int i = 0; i < getChildCount(); i++) {
                    var child = getChildAt(i);
                    if ("sep".equals(child.getTag())) child.setVisibility(wanted == VERTICAL ? View.GONE : View.VISIBLE);
                }
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
