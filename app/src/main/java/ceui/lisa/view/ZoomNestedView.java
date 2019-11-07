package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import ceui.lisa.utils.Common;

public class ZoomNestedView extends NestedScrollView {

    private float mInitialY, mInitialX;
    private boolean mIsBeingDragged;

    public ZoomNestedView(@NonNull Context context) {
        super(context);
    }

    public ZoomNestedView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ZoomNestedView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                Common.showLog("开始下拉 ACTION_DOWN");
                mInitialX = ev.getX();
                mInitialY = ev.getY();
                mIsBeingDragged = false;
                break;
            case MotionEvent.ACTION_MOVE:
                Common.showLog("开始下拉 ACTION_MOVE");
                float diffY = ev.getY() - mInitialY;
                float diffX = ev.getX() - mInitialX;
                if (diffY > 0 && diffY / Math.abs(diffX) > 2) {
                    mIsBeingDragged = true;
                    return true;
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }
}
