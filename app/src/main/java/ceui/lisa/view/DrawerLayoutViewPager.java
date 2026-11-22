package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.ScreenUtils;

import ceui.lisa.activities.Shaft;

public class DrawerLayoutViewPager extends ViewPager {

    private float startX;
    private float startY;
    private final float leftThreshold = ScreenUtils.getScreenWidth() * 0.1f;
    private int touchSlop;
    private int minFlingVelocity;
    private boolean isPotentialNegativeScreenDrag = false;
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
    }

    public void setTouchEventForwarder(IForwardTouchEvent touchEventForwarder) {
        this.touchEventForwarder = touchEventForwarder;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean negativeScreenEnabled = Shaft.sSettings != null && Shaft.sSettings.isDrawerNegativeScreenEnabled();
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                isPotentialNegativeScreenDrag = false;
                peakVelocityX = 0f;
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                velocityTracker.addMovement(ev);

                if (negativeScreenEnabled) {
                    if (getCurrentItem() != 0) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (canScroll(this, false, 1, (int) startX, (int) startY)) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                } else {
                    getParent().requestDisallowInterceptTouchEvent(!(startX < leftThreshold));
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    float currentVx = velocityTracker.getXVelocity();
                    if (currentVx > peakVelocityX) {
                        peakVelocityX = currentVx;
                    }
                }
                float dx = ev.getX() - startX;
                float dy = ev.getY() - startY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    if (negativeScreenEnabled && getCurrentItem() == 0) {
                        // 要求水平位移显著大于垂直位移（至少 1.8 倍），彻底过滤纵向瀑布流上下滑动时的微斜漂移
                        if (dx > touchSlop && dx > Math.abs(dy) * 1.8f) {
                            if (!canScroll(this, false, (int) dx, (int) ev.getX(), (int) ev.getY())) {
                                isPotentialNegativeScreenDrag = true;
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                        } else if (dx < -touchSlop && Math.abs(dx) > Math.abs(dy)) {
                            // 明确向左滑动切换 Tab
                            isPotentialNegativeScreenDrag = false;
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                boolean triggeredOpenDrawer = false;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    float endVx = velocityTracker.getXVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;

                    float upDx = ev.getX() - startX;
                    float upDy = ev.getY() - startY;

                    // 1. 跨机型自适应基准：基于 ViewConfiguration 硬件物理缩放的 4 倍最小抛掷速度
                    float flingSpeedThreshold = minFlingVelocity * 4.0f;
                    boolean isSpeedSufficient = endVx >= flingSpeedThreshold;

                    // 2. 无量纲刹车率校验：末端瞬时速度必须保持在手势峰值速度的 65% 以上
                    //    如果用户猛滑但最后刹车停顿，endVx 会被刹车抹平远低于 peakVelocityX，比值暴跌被精准过滤
                    boolean isUnbrokenFling = (peakVelocityX > 0) && (endVx >= peakVelocityX * 0.65f);

                    boolean isAngleValid = upDx > Math.abs(upDy) * 1.8f;
                    boolean isDisplacementValid = upDx > (touchSlop * 2);

                    if (negativeScreenEnabled && getCurrentItem() == 0 && isPotentialNegativeScreenDrag) {
                        if (isAngleValid && isDisplacementValid && isSpeedSufficient && isUnbrokenFling) {
                            triggeredOpenDrawer = true;
                            if (touchEventForwarder != null) {
                                touchEventForwarder.onOpenDrawer();
                            }
                        }
                    }
                }
                isPotentialNegativeScreenDrag = false;
                peakVelocityX = 0f;
                if (triggeredOpenDrawer) {
                    // 下发 CANCEL 事件，避免子控件在抬手处误触发 item 点击
                    MotionEvent cancelEvent = MotionEvent.obtain(ev);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                isPotentialNegativeScreenDrag = false;
                peakVelocityX = 0f;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        boolean negativeScreenEnabled = Shaft.sSettings != null && Shaft.sSettings.isDrawerNegativeScreenEnabled();
        boolean canScrollStart = canScroll(this, false, 1, (int) startX, (int) startY);
        boolean shouldFilter = disallowIntercept && negativeScreenEnabled && (getCurrentItem() == 0) &&
                (isPotentialNegativeScreenDrag || !canScrollStart);
        if (shouldFilter) {
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean negativeScreenEnabled = Shaft.sSettings != null && Shaft.sSettings.isDrawerNegativeScreenEnabled();
        if (negativeScreenEnabled && getCurrentItem() == 0) {
            if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = ev.getX() - startX;
                float dy = ev.getY() - startY;
                if (dx > touchSlop && dx > Math.abs(dy) * 1.8f) {
                    if (!canScroll(this, false, (int) dx, (int) ev.getX(), (int) ev.getY())) {
                        return false;
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float endX = ev.getX();
                float endY = ev.getY();
                float distanceX = endX - startX;
                float distanceY = endY - startY;
                if (Math.abs(distanceX) > Math.abs(distanceY)) {
                    if (Shaft.sSettings != null && Shaft.sSettings.isDrawerNegativeScreenEnabled()) {
                        if (getCurrentItem() == 0 && distanceX > 0) {
                            if (!canScroll(this, false, (int) distanceX, (int) endX, (int) endY)) {
                                getParent().requestDisallowInterceptTouchEvent(false);
                                if (touchEventForwarder != null) {
                                    touchEventForwarder.forwardTouchEvent(ev);
                                }
                                return true;
                            }
                        }
                    } else {
                        // 负一屏关闭时的原有逻辑保持兼容
                        if (getCurrentItem() == 0 && distanceX > 0) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                            if (touchEventForwarder != null) {
                                touchEventForwarder.forwardTouchEvent(ev);
                            }
                            return true;
                        }
                        if (getCurrentItem() != 0 && distanceX > 0 && startX < leftThreshold) {
                            if (touchEventForwarder != null) {
                                touchEventForwarder.forwardTouchEvent(ev);
                            }
                            return true;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touchEventForwarder != null) {
                    touchEventForwarder.forwardTouchEvent(ev);
                }
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
