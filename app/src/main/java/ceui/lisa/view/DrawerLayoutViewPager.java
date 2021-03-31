package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

public class DrawerLayoutViewPager extends ViewPager {

    private float startX;
    private float startY;

    private IForwardTouchEvent touchEventForwarder = null;

    public DrawerLayoutViewPager(@NonNull Context context) {
        super(context);
    }

    public DrawerLayoutViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setTouchEventForwarder(IForwardTouchEvent touchEventForwarder) {
        this.touchEventForwarder = touchEventForwarder;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 统一不允许 DrawerLayout 拦截事件，除非后面判断可以交给其消费
        getParent().requestDisallowInterceptTouchEvent(true);
        // 记录触摸开始位置
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            startX = ev.getX();
            startY = ev.getY();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 在这里收到事件，表示事件没有被可能的嵌套子控件消费
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                //计算偏移量
                float endX = ev.getX();
                float endY = ev.getY();
                float distanceX = endX - startX;
                float distanceY = endY - startY;
                if (Math.abs(distanceX) > Math.abs(distanceY)) {
                    // 当滑动到ViewPager的第0个页面，并且是从左到右滑动
                    if (getCurrentItem() == 0 && distanceX > 0) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        if(touchEventForwarder != null){
                            touchEventForwarder.forwardTouchEvent(ev);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    public interface IForwardTouchEvent {
        public void forwardTouchEvent(MotionEvent ev);
    }
}
