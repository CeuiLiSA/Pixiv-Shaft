package ceui.lisa.page.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * Created by newbiechen on 17-7-24.
 */

public class CoverPageAnim extends HorizonPageAnim {

    private final Rect mSrcRect;
    private final Rect mDestRect;
    private final GradientDrawable mBackShadowDrawableLR;

    public CoverPageAnim(int w, int h, View view, OnPageChangeListener listener) {
        super(w, h, view, listener);
        mSrcRect = new Rect(0, 0, mViewWidth, mViewHeight);
        mDestRect = new Rect(0, 0, mViewWidth, mViewHeight);
        int[] mBackShadowColors = new int[]{0x66000000, 0x00000000};
        mBackShadowDrawableLR = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, mBackShadowColors);
        mBackShadowDrawableLR.setGradientType(GradientDrawable.LINEAR_GRADIENT);
    }

    @Override
    public void drawStatic(Canvas canvas) {
        if (isCancel) {
            mNextBitmap = mCurBitmap.copy(Bitmap.Config.RGB_565, true);
            canvas.drawBitmap(mCurBitmap, 0, 0, null);
        } else {
            canvas.drawBitmap(mNextBitmap, 0, 0, null);
        }
    }

    @Override
    public void drawMove(Canvas canvas) {

        switch (mDirection) {
            case NEXT:
                int dis = (int) (mViewWidth - mStartX + mTouchX);
                if (dis > mViewWidth) {
                    dis = mViewWidth;
                }
                //计算bitmap截取的区域
                mSrcRect.left = mViewWidth - dis;
                //计算bitmap在canvas显示的区域
                mDestRect.right = dis;
                canvas.drawBitmap(mNextBitmap, 0, 0, null);
                canvas.drawBitmap(mCurBitmap, mSrcRect, mDestRect, null);
                addShadow(dis, canvas);
                break;
            default:
                mSrcRect.left = (int) (mViewWidth - mTouchX);
                mDestRect.right = (int) mTouchX;
                canvas.drawBitmap(mCurBitmap, 0, 0, null);
                canvas.drawBitmap(mNextBitmap, mSrcRect, mDestRect, null);
                addShadow((int) mTouchX, canvas);
                break;
        }
    }

    //添加阴影
    public void addShadow(int left, Canvas canvas) {
        mBackShadowDrawableLR.setBounds(left, 0, left + 30, mScreenHeight);
        mBackShadowDrawableLR.draw(canvas);
    }

    @Override
    public void startAnim() {
        super.startAnim();
        int dx = 0;
        switch (mDirection) {
            case NEXT:
                if (isCancel) {
                    int dis = (int) ((mViewWidth - mStartX) + mTouchX);
                    if (dis > mViewWidth) {
                        dis = mViewWidth;
                    }
                    dx = mViewWidth - dis;
                } else {
                    dx = (int) -(mTouchX + (mViewWidth - mStartX));
                }
                break;
            default:
                if (isCancel) {
                    dx = (int) -mTouchX;
                } else {
                    dx = (int) (mViewWidth - mTouchX);
                }
                break;
        }

        //滑动速度保持一致
        int duration = (400 * Math.abs(dx)) / mViewWidth;
        mScroller.startScroll((int) mTouchX, 0, dx, 0, duration);
    }
}
