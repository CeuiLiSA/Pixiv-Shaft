package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import ceui.lisa.utils.DensityUtil;

public class Scroll extends NestedScrollView {

    public Scroll(@NonNull Context context) {
        super(context);
    }

    public Scroll(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public Scroll(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int measureWidth = MeasureSpec.getSize(widthMeasureSpec);
        int measureHeight = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(measureWidth, measureHeight - DensityUtil.dp2px(96.0f));
    }
}
