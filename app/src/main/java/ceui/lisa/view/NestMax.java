package ceui.lisa.view;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

public class NestMax extends NestedScrollView {

    DisplayMetrics mDisplayMetrics;

    public NestMax(@NonNull Context context) {
        super(context);
        init();
    }

    public NestMax(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NestMax(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        Display display = ((Activity) getContext()).getWindowManager().getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        display.getMetrics(mDisplayMetrics);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        try {
            //最大高度显示为屏幕内容高度的一半

            //此处是关键，设置控件高度不能超过屏幕高度一半（d.heightPixels / 2）（在此替换成自己需要的高度）
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mDisplayMetrics.heightPixels / 2, MeasureSpec.AT_MOST);

        } catch (Exception e) {
            e.printStackTrace();
        }
        //重新计算控件高、宽
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

    }
}
