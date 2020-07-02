package ceui.lisa.view;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Scroller;

import ceui.lisa.utils.Common;


public class BottomSlideLayout extends LinearLayout {


    GestureDetector mDetector;

    public BottomSlideLayout(Context context) {
        super(context);
    }

    public BottomSlideLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BottomSlideLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private View bottomBar;

    private View bottomContent;

    private Scroller mScroller;

    /** 控制栏的可视范围 */
    private Rect barRect;

    private int downX, downY;
    private int scrollOffset;


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        initView();
    }

    private void initView() {
        int count = getChildCount();
        if(count != 2){
            return;
        }
        bottomBar = getChildAt(0);
        bottomContent =getChildAt(1);
        barRect = new Rect();
        mScroller = new Scroller(getContext());
        bottomBar.getGlobalVisibleRect(barRect);
        mDetector = new GestureDetector(getContext(), new GestureDetector.OnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return false;
            }

            @Override
            public void onShowPress(MotionEvent e) {

            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                return false;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                Common.showLog("BottomSlideLayout onLongPress");
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                Common.showLog("BottomSlideLayout onLongPress");
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                Common.showLog("BottomSlideLayout onFling");
                return false;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        switch (event.getAction()){
            case MotionEvent.ACTION_DOWN:
                downX = (int) event.getX();
                downY = (int) event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                int endY = (int) event.getY();
                int dy = (int) (endY - downY);
                int toScroll = getScrollY() - dy;
                if(toScroll < 0){
                    toScroll = 0;
                } else if(toScroll > bottomContent.getMeasuredHeight()){
                    toScroll = bottomContent.getMeasuredHeight();
                }
                scrollTo(0, toScroll);
                downY = (int) event.getY();
                break;
            case MotionEvent.ACTION_UP:
                scrollOffset = getScrollY();
                if(scrollOffset > bottomContent.getMeasuredHeight() / 2){
                    showNavigation();
                } else {
                    closeNavigation();
                }
                break;
        }
//        mDetector.onTouchEvent(event);

        return true;
    }

    private void showNavigation(){
        int dy = bottomContent.getMeasuredHeight() - scrollOffset;
        mScroller.startScroll(getScrollX(), getScrollY(), 0, dy, 500);
        invalidate();
    }

    private void closeNavigation(){
        int dy = 0 - scrollOffset;
        mScroller.startScroll(getScrollX(), getScrollY(), 0, dy, 500);
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        bottomBar.layout(0, getMeasuredHeight() - bottomBar.getMeasuredHeight(), getMeasuredWidth(), getMeasuredHeight());
        bottomContent.layout(0, getMeasuredHeight(), getMeasuredWidth(), bottomBar.getBottom() + bottomContent.getMeasuredHeight());
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if(mScroller.computeScrollOffset()){
            scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
            invalidate();
        }
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return super.onNestedFling(target, velocityX, velocityY, consumed);

    }
}
