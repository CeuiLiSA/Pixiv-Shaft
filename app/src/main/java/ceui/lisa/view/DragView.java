package ceui.lisa.view;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.design.widget.AppBarLayout;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringSystem;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import de.hdodenhof.circleimageview.CircleImageView;

public class DragView extends RelativeLayout {

    ViewDragHelper mViewDragHelper;
    private int originX, originY;
    private AnimateImageView mImageView;


    public DragView(Context context) {
        super(context);
    }

    public DragView(Context context, AttributeSet attrs) {
        super(context, attrs);


        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                originX = getChildAt(0).getLeft();
                originY = getChildAt(0).getTop();



                mImageView = (AnimateImageView) getChildAt(0);


                mImageView.setCurrentSpringPos(originX, originY);

                getViewTreeObserver()
                        .removeOnGlobalLayoutListener(this);
            }
        });


    }

    public DragView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DragView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        //中间参数表示灵敏度,比如滑动了多少像素才视为触发了滑动.值越大越灵敏.
        mViewDragHelper = ViewDragHelper.create(this, 1f, new ViewDragCallback());
    }

    private class ViewDragCallback extends ViewDragHelper.Callback{

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            //child 表示想要滑动的view
            //pointerId 表示触摸点的id, 比如多点按压的那个id
            //返回值表示,是否可以capture,也就是是否可以滑动.可以根据不同的child决定是否可以滑动
            if(child.getId() == mImageView.getId()){
                Common.showLog("DragView 点击了父亲");
                return true;
            }
            return false;
        }


        @Override
        public int getViewVerticalDragRange(View child) {
            return 1;
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            //child 表示当前正在移动的view
            //left 表示当前的view正要移动到左边距为left的地方
            //dx 表示和上一次滑动的距离间隔
            //返回值就是child要移动的目标位置.可以通过控制返回值,从而控制child只能在ViewGroup的范围中移动.
            return left;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            //child 表示当前正在移动的view
            //top 表示当前的view正要移动到上边距为top的地方
            //dx 表示和上一次滑动的距离间隔
            return top;
        }

        @Override
        public void onViewReleased(@NonNull View releasedChild, float xvel, float yvel) {
            super.onViewReleased(releasedChild, xvel, yvel);


            mImageView.onRelease(originX, originY);
        }

        @Override
        public void onViewPositionChanged(@NonNull View changedView, int left, int top, int dx, int dy) {

            Common.showLog(left + " " + top);
            mImageView.animTo(left, top);
        }
    }



    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mViewDragHelper.shouldInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //固定写法
        mViewDragHelper.processTouchEvent(event);
        return true;
    }

    @Override
    public void computeScroll() {
        //固定写法
        //此方法用于自动滚动,比如自动回滚到默认位置.
        if (mViewDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }
}
