package ceui.lisa.view;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import android.util.AttributeSet;

import ceui.lisa.utils.Common;

public class ExpandCard extends CardView {

    private boolean isExpand = false;
    private int maxHeight = 0;

    public boolean isAutoHeight() {
        return autoHeight;
    }

    public void setAutoHeight(boolean autoHeight) {
        this.autoHeight = autoHeight;
    }

    private boolean autoHeight = true;

    public int getRealHeight() {
        return realHeight;
    }

    public void setRealHeight(int realHeight) {
        this.realHeight = realHeight;
    }

    private int realHeight = 0;
    private Context mContext;

    public ExpandCard(@NonNull Context context) {
        super(context);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        if (isAutoHeight()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            if (isExpand) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            } else {
                setMeasuredDimension(widthMeasureSpec, maxHeight);
            }
        }
    }

    private int getSize(int measureSpec) {
        int result = 0;
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (specMode) {
            case MeasureSpec.EXACTLY:
                //当layout_width与layout_height　match_parent 为固定数值走这里
                result = maxHeight;
                Common.showLog("ExpandCard EXACTLY ");
                break;
            case MeasureSpec.AT_MOST:
                //当layout_width与layout_height定义为 wrap_content　就走这里
                result = Math.min(maxHeight, specSize);
                Common.showLog("ExpandCard AT_MOST ");
                break;
            case MeasureSpec.UNSPECIFIED:
                //如果没有指定大小
                result = Math.min(maxHeight, realHeight);
                Common.showLog("ExpandCard UNSPECIFIED ");
                break;
        }
        return result;
    }

    public boolean isExpand() {
        return isExpand;
    }

    public void setExpand(boolean expand) {
        isExpand = expand;
    }


    public int getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }
}
