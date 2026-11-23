package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.ScreenUtils;

import ceui.lisa.activities.Shaft;

public class DrawerLayoutViewPager extends ViewPager {

    private static final float FLING_VELOCITY_MULTIPLIER = 4.0f;
    private static final float BRAKING_RATIO_THRESHOLD = 0.65f;
    private static final float HORIZONTAL_ANGLE_RATIO = 1.8f;

    private float startX;
    private float startY;
    private final float leftThreshold = ScreenUtils.getScreenWidth() * 0.1f;
    private int touchSlop;
    private int minFlingVelocity;
    private int maxFlingVelocity;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean trackingNegativeScreen = false;
    private boolean forwardedMoveToDrawer = false;
    private float peakVelocityX = 0f;
    private VelocityTracker velocityTracker = null;

    private IForwardTouchEvent touchEventForwarder = null;

    public DrawerLayoutViewPager(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DrawerLayoutViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
    }

    public void setTouchEventForwarder(IForwardTouchEvent touchEventForwarder) {
        this.touchEventForwarder = touchEventForwarder;
    }

    private boolean isNegativeScreenEnabled() {
        return Shaft.sSettings != null && Shaft.sSettings.isDrawerNegativeScreenEnabled();
    }

    private void resetNegativeScreenTracking() {
        trackingNegativeScreen = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        peakVelocityX = 0f;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        resetNegativeScreenTracking();
        forwardedMoveToDrawer = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                forwardedMoveToDrawer = false;
                resetNegativeScreenTracking();

                // 如果触发左边缘触摸，交给 DrawerLayout 处理；
                // 其他情况禁止 DrawerLayout 拦截，避免全屏 mEdgeSize 误吞左滑切页、竖向滚动与慢速拖拽。
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(!(startX < leftThreshold));
                }

                // 负一屏猛甩仅在落指瞬间已处于最左侧边界时才启用追踪；
                // 子 ViewPager 或横向货架原本仍可向右滑时，不允许滑到边界后半途升级为开抽屉。
                if (isNegativeScreenEnabled()
                        && getCurrentItem() == 0
                        && !canScroll(this, true, 1, (int) startX, (int) startY)) {
                    trackingNegativeScreen = true;
                    activePointerId = ev.getPointerId(0);
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(ev);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 多指手势不作为单指负一屏猛甩处理，避免主指抬起切换指针后引发位移/速度跳变。
                if (trackingNegativeScreen) {
                    resetNegativeScreenTracking();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (trackingNegativeScreen && velocityTracker != null) {
                    int pointerIndex = ev.findPointerIndex(activePointerId);
                    if (pointerIndex < 0) {
                        resetNegativeScreenTracking();
                        break;
                    }
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float currentVx = velocityTracker.getXVelocity(activePointerId);
                    if (currentVx > peakVelocityX) {
                        peakVelocityX = currentVx;
                    }
                    float dx = ev.getX(pointerIndex) - startX;
                    float dy = ev.getY(pointerIndex) - startY;
                    // 一旦明确进入左滑（切页 / 滑货架）或竖向滚动，整场手势不再允许触发负一屏。
                    if (dx < -touchSlop
                            || (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx))) {
                        resetNegativeScreenTracking();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean triggeredOpenDrawer = false;
                if (trackingNegativeScreen && velocityTracker != null) {
                    int pointerIndex = ev.findPointerIndex(activePointerId);
                    if (pointerIndex >= 0 && getCurrentItem() == 0 && isNegativeScreenEnabled()) {
                        velocityTracker.addMovement(ev);
                        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                        float endVx = velocityTracker.getXVelocity(activePointerId);
                        if (endVx > peakVelocityX) {
                            peakVelocityX = endVx;
                        }

                        float upDx = ev.getX(pointerIndex) - startX;
                        float upDy = ev.getY(pointerIndex) - startY;

                        // 1. 跨机型自适应基准：基于 ViewConfiguration 硬件物理缩放的 4 倍最小抛掷速度
                        float flingSpeedThreshold = minFlingVelocity * FLING_VELOCITY_MULTIPLIER;
                        boolean isSpeedSufficient = endVx >= flingSpeedThreshold;

                        // 2. 无量纲刹车率校验：末端瞬时速度必须保持在手势峰值速度的 65% 以上
                        //    如果用户猛滑但最后刹车停顿，endVx 会被刹车抹平远低于 peakVelocityX，比值暴跌被精准过滤
                        boolean isUnbrokenFling = (peakVelocityX > 0f)
                                && (endVx >= peakVelocityX * BRAKING_RATIO_THRESHOLD);

                        boolean isAngleValid = upDx > Math.abs(upDy) * HORIZONTAL_ANGLE_RATIO;
                        boolean isDisplacementValid = upDx > (touchSlop * 2);

                        if (isAngleValid && isDisplacementValid && isSpeedSufficient && isUnbrokenFling) {
                            triggeredOpenDrawer = true;
                        }
                    }
                }
                resetNegativeScreenTracking();
                if (triggeredOpenDrawer) {
                    // 先下发 CANCEL 清理子控件按压态与 ViewPager 拖拽态，避免抬手误触 item 点击，再打开抽屉
                    MotionEvent cancelEvent = MotionEvent.obtain(ev);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                    if (touchEventForwarder != null) {
                        touchEventForwarder.onOpenDrawer();
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                resetNegativeScreenTracking();
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 在这里收到事件，表示事件没有被可能的嵌套子控件消费
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float endX = ev.getX();
                float endY = ev.getY();
                float distanceX = endX - startX;
                float distanceY = endY - startY;
                if (Math.abs(distanceX) > Math.abs(distanceY)) {
                    // 负一屏关闭时维持首页原有向右跟手拉抽屉行为；
                    // 负一屏开启时首页仅在 dispatchTouchEvent 抬手猛甩结算，不在此处提前透传给 DrawerLayout。
                    if (!isNegativeScreenEnabled() && getCurrentItem() == 0 && distanceX > 0) {
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(false);
                        }
                        if (touchEventForwarder != null) {
                            forwardedMoveToDrawer = true;
                            touchEventForwarder.forwardTouchEvent(ev);
                        }
                        return true;
                    }
                    // 不是首页，且从左边缘向右滑动打开抽屉
                    if (getCurrentItem() != 0 && distanceX > 0 && startX < leftThreshold) {
                        if (touchEventForwarder != null) {
                            forwardedMoveToDrawer = true;
                            touchEventForwarder.forwardTouchEvent(ev);
                        }
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (forwardedMoveToDrawer && touchEventForwarder != null) {
                    forwardedMoveToDrawer = false;
                    touchEventForwarder.forwardTouchEvent(ev);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                // 禁止向 DrawerLayout 透传 ACTION_CANCEL：该事件往往来自 DrawerLayout 自身拦截
                // 或本类触发 onOpenDrawer 后的子视图清理，透传会触发 mLeftDragger.cancel() 立即中止抽屉动作。
                forwardedMoveToDrawer = false;
                break;
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    public interface IForwardTouchEvent {
        void forwardTouchEvent(MotionEvent ev);
        default void onOpenDrawer() {}
    }
}
