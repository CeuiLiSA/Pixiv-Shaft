package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * Created by kalshen on 2018/4/20.
 * 傻*scrollView
 * 垂直viewpager和scrollview的滚动冲突解决
 */
public class FoolishScrollView extends ScrollView {


    public FoolishScrollView(Context context) {
        super(context);
    }

    public FoolishScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private int mLastY = 0;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int y = (int) ev.getY();
        View childView = getChildAt(0);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLastY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mLastY-y> 0&&childView != null && childView.getMeasuredHeight() <= getScrollY() + getHeight()) {
                    return false;
                }
                break;
        }
        return super.onTouchEvent(ev);
    }

}