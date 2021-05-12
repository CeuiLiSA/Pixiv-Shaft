package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DynamicHeightImageView extends androidx.appcompat.widget.AppCompatImageView {

    private float mHeightRatio;
    private ScaleType tmpScaleType;

    public DynamicHeightImageView(Context context) {
        super(context);
    }

    public DynamicHeightImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DynamicHeightImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setHeightRatio(float ratio) {
        if (ratio != mHeightRatio) {
            mHeightRatio = ratio;
            requestLayout();
        }
    }

    public void setHeightRatioAndScaleType(float ratio, ScaleType scaleType) {
        boolean b1 = ratio != mHeightRatio;
        if (b1) {
            mHeightRatio = ratio;
            requestLayout();
        }
        tmpScaleType = scaleType;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mHeightRatio > 0.0) {
            // set the image views size
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width * mHeightRatio);
            setMeasuredDimension(width, height);
            if(tmpScaleType != null && tmpScaleType != getScaleType()){
                setScaleType(tmpScaleType);
            }
        }
        else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
