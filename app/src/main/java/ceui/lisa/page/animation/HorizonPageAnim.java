package ceui.lisa.page.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;


/**
 * Created by newbiechen on 17-7-24.
 * 横向动画的模板
 */

public abstract class HorizonPageAnim extends PageAnimation {
    private static final String TAG = "HorizonPageAnim";

    protected Bitmap mCurBitmap;
    protected Bitmap mNextBitmap;
    //是否取消翻页
    protected boolean isCancel = false;
    // move时是否执行过一次hasPre或hasNext方法(下载时候按住不放 会执行两次，所以加此标志只执行一次)
    boolean executePreOrNextPage;
    private int x;
    private int y;
    //可以使用 mLast代替
    private int mMoveX = 0;
    private int mMoveY = 0;
    //是否移动了
    private boolean isMove = false;
    //是否翻阅下一页。true表示翻到下一页，false表示上一页。
    private boolean isNext = false;
    //是否没下一页或者上一页
    private boolean noNext = false;

    public HorizonPageAnim(int w, int h, View view, OnPageChangeListener listener) {
        this(w, h, 0, 0, view, listener);
    }

    public HorizonPageAnim(int w, int h, int marginWidth, int marginHeight,
                           View view, OnPageChangeListener listener) {
        super(w, h, marginWidth, marginHeight, view, listener);
        //创建图片
        mCurBitmap = Bitmap.createBitmap(mViewWidth, mViewHeight, Bitmap.Config.RGB_565);
        mNextBitmap = Bitmap.createBitmap(mViewWidth, mViewHeight, Bitmap.Config.RGB_565);
    }

    /**
     * 转换页面，在显示下一章的时候，必须首先调用此方法
     */
    public void changePage() {
        Bitmap bitmap = mCurBitmap;
        mCurBitmap = mNextBitmap;
        mNextBitmap = bitmap;
    }

    public abstract void drawStatic(Canvas canvas);

    public abstract void drawMove(Canvas canvas);

    private void simulationTouchDown(int x, int y) {
        //移动的点击位置
        mMoveX = 0;
        mMoveY = 0;
        //是否移动
        isMove = false;
        //是否存在下一章
        noNext = false;
        //是下一章还是前一章
        isNext = false;
        //是否正在执行动画
        isRunning = false;
        //取消
        isCancel = false;
        executePreOrNextPage = true;
        //设置起始位置的触摸点
        setStartPoint(x, y);
        //如果存在动画则取消动画
        abortAnim();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        //获取点击位置
        x = (int) event.getX();
        y = (int) event.getY();
        //设置触摸点
        setTouchPoint(x, y);

//        if ((mListener != null && mListener.isRequesting()))
//            return true;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                simulationTouchDown(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                final int slop = ViewConfiguration.get(mView.getContext()).getScaledTouchSlop();
                //判断是否移动了
                if (!isMove) {
                    isMove = Math.abs(mStartX - x) > slop || Math.abs(mStartY - y) > slop;
                }

                if (isMove) {
                    {
                        //判断是否是准备移动的状态(将要移动但是还没有移动)
                        if (mMoveX == 0 && mMoveY == 0) {

                            //判断翻得是上一页还是下一页
                            if (x - mStartX > 0) {
                                //上一页的参数配置
                                isNext = false;
                                boolean hasPrev = mListener.hasPrev(executePreOrNextPage);
                                executePreOrNextPage = false;
                                setDirection(Direction.PRE);
                                //如果上一页不存在
                                if (!hasPrev) {
                                    noNext = true;
                                    return true;
                                }
                            } else {
                                //进行下一页的配置
                                isNext = true;
                                //判断是否下一页存在
                                boolean hasNext = mListener.hasNext(executePreOrNextPage);
                                executePreOrNextPage = false;
                                //如果存在设置动画方向
                                setDirection(Direction.NEXT);

                                //如果不存在表示没有下一页了
                                if (!hasNext) {
                                    noNext = true;
                                    return true;
                                }
                            }
                        } else {
                            //判断是否取消翻页
                            if (isNext) {
                                isCancel = x - mMoveX > 0;

                            } else {
                                isCancel = x - mMoveX < 0;
                            }
                        }
                    }

                    mMoveX = x;
                    mMoveY = y;
                    isRunning = true;

                    // 防止无动画抖动
                    if (!(this instanceof NonePageAnim))
                        mView.invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:

                if (!isMove) {

                    isNext = x >= mScreenWidth / 2;

                    if (isNext) {
                        //判断是否下一页存在
                        boolean hasNext = mListener.hasNext(true);
                        //设置动画方向
                        setDirection(Direction.NEXT);

                        if (!hasNext) {
                            return true;
                        }
                    } else {
                        boolean hasPrev = mListener.hasPrev(true);
                        setDirection(Direction.PRE);
                        if (!hasPrev) {
                            return true;
                        }
                    }
                }

                // 是否取消翻页
                if (isCancel) {
                    // 无动画，不执行取消回调
                    if (!(this instanceof NonePageAnim))
                        mListener.pageCancel();
                } else {
                    mListener.turnPage();
                }

                // 开启翻页效果
                if (!noNext) {
                    startAnim();
                    mView.invalidate();
                }
                break;
        }
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (isRunning) {
            drawMove(canvas);
        } else {
            if (isCancel) {
                mNextBitmap = mCurBitmap.copy(Bitmap.Config.RGB_565, true);
            }
            drawStatic(canvas);
        }
    }

    @Override
    public void scrollAnim() {
        if (mScroller.computeScrollOffset()) {
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();

            setTouchPoint(x, y);

            if (mScroller.getFinalX() == x && mScroller.getFinalY() == y) {
                isRunning = false;

                // 自动翻页后，重新记录下down的位置，否则翻页前的动画不知道down的起始点
                if (autoPageIsRunning) {
                    isCancel = true;
                    simulationTouchDown(0, 0);
                    setTouchPoint(mScroller.getFinalX(), mScroller.getFinalY());
                    autoPageIsRunning = false;
                }
            }
            mView.postInvalidate();
        }
    }

    @Override
    public void abortAnim() {
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
            isRunning = false;
            autoPageIsRunning = false;
            setTouchPoint(mScroller.getFinalX(), mScroller.getFinalY());
            mView.postInvalidate();
        }
    }

    @Override
    public Bitmap getBgBitmap() {
        return mNextBitmap;
    }

    @Override
    public Bitmap getNextBitmap() {
        return mNextBitmap;
    }
}
