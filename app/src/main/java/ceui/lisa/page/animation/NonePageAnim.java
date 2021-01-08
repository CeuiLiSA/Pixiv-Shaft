package ceui.lisa.page.animation;

import android.graphics.Canvas;
import android.view.View;

/**
 * Created by newbiechen on 17-7-24.
 */
public class NonePageAnim extends HorizonPageAnim {

    float downX, downY;
    boolean isMove;
    int slop;
    boolean hasPreOrNext;


    public NonePageAnim(int w, int h, View view, OnPageChangeListener listener) {
        super(w, h, view, listener);
    }

    @Override
    public void drawStatic(Canvas canvas) {
        if (isCancel) {
            canvas.drawBitmap(mCurBitmap, 0, 0, null);
        } else {
            canvas.drawBitmap(mNextBitmap, 0, 0, null);
        }
    }

    @Override
    public void drawMove(Canvas canvas) {
        if (isCancel) {
            canvas.drawBitmap(mCurBitmap, 0, 0, null);
        } else {
            canvas.drawBitmap(mNextBitmap, 0, 0, null);
        }
    }

    @Override
    public void startAnim() {
    }

//    @Override
//    public boolean onTouchEvent(MotionEvent event) {
//        switch (event.getAction()) {
//            case MotionEvent.ACTION_DOWN:
//                downX = event.getX();
//                downY = event.getY();
//                isMove = false;
//                break;
//
//            case MotionEvent.ACTION_MOVE:
//                if (downX > 0 || downY > 0)
//                    isMove = true;
//                break;
//
//            case MotionEvent.ACTION_UP:
//                // 移动
//                if (isMove) {
//
//                    if (slop < 1)
//                        slop = ViewConfiguration.get(mView.getContext()).getScaledTouchSlop() * 2;
//
//                    if (event.getX() - downX < -slop) {
//                        hasPreOrNext = mListener.hasNext();
//                    } else if (event.getX() - downX > slop) {
//                        hasPreOrNext = mListener.hasPrev();
//                    } else {
//                        return true;
//                    }
//                }
//                // 直接点击
//                else {
//                    if (downX < mScreenWidth / 2) {
//                        hasPreOrNext = mListener.hasPrev();
//                    } else {
//                        hasPreOrNext = mListener.hasNext();
//                    }
//                }
//
//                // 下一页有数据执行翻页
//                if (hasPreOrNext)
//                    mListener.turnPage();
//                else
//                    mListener.pageCancel();
//
//                downX = 0;
//                downY = 0;
//                isMove = false;
//
//                mView.invalidate();
//
//                break;
//        }
//
//        return true;
//    }
}
