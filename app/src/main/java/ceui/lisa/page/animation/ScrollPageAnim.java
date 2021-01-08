package ceui.lisa.page.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Created by newbiechen on 17-7-23.
 * 原理:仿照ListView源码实现的上下滑动效果
 * Alter by: zeroAngus
 * <p>
 * 问题:
 * 1. 向上翻页，重复的问题 (完成)
 * 2. 滑动卡顿的问题。原因:由于绘制的数据过多造成的卡顿问题。 (主要是文字绘制需要的时长比较多) 解决办法：做文字缓冲
 * 3. 弱网环境下，显示的问题
 */
public class ScrollPageAnim extends PageAnimation {
    private static final String TAG = "ScrollAnimation";
    // 滑动追踪的时间
    private static final int VELOCITY_DURATION = 1000;
    BitmapView tmpView;
    private VelocityTracker mVelocity;
    // 整个Bitmap的背景显示
    private Bitmap mBgBitmap;
    // 下一个展示的图片
    private Bitmap mNextBitmap;
    // 被废弃的图片列表
    private ArrayDeque<BitmapView> mScrapViews;
    // 正在被利用的图片列表
    private final ArrayList<BitmapView> mActiveViews = new ArrayList<>(2);
    // 是否处于刷新阶段
    private boolean isRefresh = true;
    // 底部填充
    private Iterator<BitmapView> downIt;
    private Iterator<BitmapView> upIt;

    public ScrollPageAnim(int w, int h, int marginWidth, int marginHeight,
                          View view, OnPageChangeListener listener) {
        super(w, h, marginWidth, marginHeight, view, listener);
        // 创建两个BitmapView
        initWidget();
    }

    private void initWidget() {
        mBgBitmap = Bitmap.createBitmap(mScreenWidth, mScreenHeight, Bitmap.Config.RGB_565);

        mScrapViews = new ArrayDeque<>(2);
        for (int i = 0; i < 2; ++i) {
            BitmapView view = new BitmapView();
            view.bitmap = Bitmap.createBitmap(mViewWidth, mViewHeight, Bitmap.Config.RGB_565);
            view.srcRect = new Rect(0, 0, mViewWidth, mViewHeight);
            view.destRect = new Rect(0, 0, mViewWidth, mViewHeight);
            view.top = 0;
            view.bottom = view.bitmap.getHeight();

            mScrapViews.push(view);
        }
        onLayout();
        isRefresh = false;
    }

    // 修改布局,填充内容
    private void onLayout() {
        // 如果还没有开始加载，则从上到下进行绘制
        if (mActiveViews.size() == 0) {
            fillDown(0, 0);
            mDirection = Direction.NONE;
        } else {
            int offset = (int) (mTouchY - mLastY);
            // 判断是下滑还是上拉 (下滑)
            if (offset > 0) {
                int topEdge = mActiveViews.get(0).top;
                fillUp(topEdge, offset);
            }
            // 上拉
            else {
                // 底部的距离 = 当前底部的距离 + 滑动的距离 (因为上滑，得到的值肯定是负的)
                int bottomEdge = mActiveViews.get(mActiveViews.size() - 1).bottom;
                fillDown(bottomEdge, offset);
            }
        }
    }

    /**
     * 创建View填充底部空白部分
     *
     * @param bottomEdge :当前最后一个View的底部，在整个屏幕上的位置,即相对于屏幕顶部的距离
     * @param offset     :滑动的偏移量
     */
    private void fillDown(int bottomEdge, int offset) {

        downIt = mActiveViews.iterator();
        BitmapView view;

        // 进行删除
        while (downIt.hasNext()) {
            view = downIt.next();
            view.top = view.top + offset;
            view.bottom = view.bottom + offset;
            // 设置允许显示的范围
            view.destRect.top = view.top;
            view.destRect.bottom = view.bottom;

            // 判断是否越界了
            if (view.bottom <= 0) {
                // 添加到废弃的View中
                mScrapViews.add(view);
                // 从Active中移除
                downIt.remove();
                // 如果原先是从上加载，现在变成从下加载，则表示取消
                if (mDirection == Direction.UP) {
                    mListener.pageCancel();
                    mDirection = Direction.NONE;
                }
            }
        }

        // 滑动之后的最后一个 View 的距离屏幕顶部上的实际位置
        int realEdge = bottomEdge + offset;

        // 进行填充
        while (realEdge < mViewHeight && mActiveViews.size() < 2) {
            // 从废弃的Views中获取一个
            view = mScrapViews.getFirst();
/*          //擦除其Bitmap(重新创建会不会更好一点)
            eraseBitmap(view.bitmap,view.bitmap.getWidth(),view.bitmap.getHeight(),0,0);*/
            if (view == null) return;

            Bitmap cancelBitmap = mNextBitmap;
            mNextBitmap = view.bitmap;

            if (!isRefresh) {
                boolean hasNext = mListener.hasNext(true); //如果不成功则无法滑动

                // 如果不存在next,则进行还原
                if (!hasNext) {
                    mNextBitmap = cancelBitmap;
                    for (BitmapView activeView : mActiveViews) {
                        activeView.top = 0;
                        activeView.bottom = mViewHeight;
                        // 设置允许显示的范围
                        activeView.destRect.top = activeView.top;
                        activeView.destRect.bottom = activeView.bottom;
                    }
                    abortAnim();
                    return;
                }
            }

            // 如果加载成功，那么就将View从ScrapViews中移除
            mScrapViews.removeFirst();
            // 添加到存活的Bitmap中
            mActiveViews.add(view);
            mDirection = Direction.DOWN;

            // 设置Bitmap的范围
            view.top = realEdge;
            view.bottom = realEdge + view.bitmap.getHeight();
            // 设置允许显示的范围
            view.destRect.top = view.top;
            view.destRect.bottom = view.bottom;

            realEdge += view.bitmap.getHeight();
        }
    }

    /**
     * 创建View填充顶部空白部分
     *
     * @param topEdge : 当前第一个View的顶部，到屏幕顶部的距离
     * @param offset  : 滑动的偏移量
     */
    private void fillUp(int topEdge, int offset) {
        // 首先进行布局的调整
        upIt = mActiveViews.iterator();
        BitmapView view;
        while (upIt.hasNext()) {
            view = upIt.next();
            view.top = view.top + offset;
            view.bottom = view.bottom + offset;
            //设置允许显示的范围
            view.destRect.top = view.top;
            view.destRect.bottom = view.bottom;

            // 判断是否越界了
            if (view.top >= mViewHeight) {
                // 添加到废弃的View中
                mScrapViews.add(view);
                // 从Active中移除
                upIt.remove();

                // 如果原先是下，现在变成从上加载了，则表示取消加载
                if (mDirection == Direction.DOWN) {
                    mListener.pageCancel();
                    mDirection = Direction.NONE;
                }
            }
        }

        // 滑动之后，第一个 View 的顶部距离屏幕顶部的实际位置。
        int realEdge = topEdge + offset;

        // 对布局进行View填充
        while (realEdge > 0 && mActiveViews.size() < 2) {
            // 从废弃的Views中获取一个
            view = mScrapViews.getFirst();
            if (view == null) return;

            // 判断是否存在上一章节
            Bitmap cancelBitmap = mNextBitmap;
            mNextBitmap = view.bitmap;
            if (!isRefresh) {
                boolean hasPrev = mListener.hasPrev(true); // 如果不成功则无法滑动
                // 如果不存在next,则进行还原
                if (!hasPrev) {
                    mNextBitmap = cancelBitmap;
                    for (BitmapView activeView : mActiveViews) {
                        activeView.top = 0;
                        activeView.bottom = mViewHeight;
                        // 设置允许显示的范围
                        activeView.destRect.top = activeView.top;
                        activeView.destRect.bottom = activeView.bottom;
                    }
                    abortAnim();
                    return;
                }
            }
            // 如果加载成功，那么就将View从ScrapViews中移除
            mScrapViews.removeFirst();
            // 加入到存活的对象中
            mActiveViews.add(0, view);
            mDirection = Direction.UP;
            // 设置Bitmap的范围
            view.top = realEdge - view.bitmap.getHeight();
            view.bottom = realEdge;

            // 设置允许显示的范围
            view.destRect.top = view.top;
            view.destRect.bottom = view.bottom;
            realEdge -= view.bitmap.getHeight();
        }
    }

    /**
     * 对Bitmap进行擦除
     *
     * @param b
     * @param width
     * @param height
     * @param paddingLeft
     * @param paddingTop
     */
    private void eraseBitmap(Bitmap b, int width, int height,
                             int paddingLeft, int paddingTop) {
     /*   if (mInitBitmapPix == null) return;
        b.setPixels(mInitBitmapPix, 0, width, paddingLeft, paddingTop, width, height);*/
    }

    /**
     * 重置位移
     */
    public void resetBitmap() {
        isRefresh = true;
        // 将所有的Active加入到Scrap中
        for (BitmapView view : mActiveViews) {
            mScrapViews.add(view);
        }
        // 清除所有的Active
        mActiveViews.clear();
        // 重新进行布局
        onLayout();
        isRefresh = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int x = (int) event.getX();
        int y = (int) event.getY();

        // 初始化速度追踪器
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }

        mVelocity.addMovement(event);
        // 设置触碰点
        setTouchPoint(x, y);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isRunning = false;
                // 设置起始点
                setStartPoint(x, y);
                // 停止动画
                abortAnim();
                break;
            case MotionEvent.ACTION_MOVE:
                mVelocity.computeCurrentVelocity(VELOCITY_DURATION);
                isRunning = true;
                // 进行刷新
                mView.postInvalidate();
                break;
            case MotionEvent.ACTION_UP:
                isRunning = false;
                // 开启动画
                startAnim();
                // 删除检测器
                mVelocity.recycle();
                mVelocity = null;
                break;

            case MotionEvent.ACTION_CANCEL:
                try {
                    mVelocity.recycle(); // if velocityTracker won't be used should be recycled
                    mVelocity = null;
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
        }
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        //进行布局
        onLayout();

        //绘制背景
        canvas.drawBitmap(mBgBitmap, 0, 0, null);
        //绘制内容
        canvas.save();
        //移动位置
        canvas.translate(0, mMarginHeight);
        //裁剪显示区域
        canvas.clipRect(0, 0, mViewWidth, mViewHeight);
/*        //设置背景透明
        canvas.drawColor(0x40);*/
        //绘制Bitmap
        for (int i = 0; i < mActiveViews.size(); ++i) {
            tmpView = mActiveViews.get(i);
            canvas.drawBitmap(tmpView.bitmap, tmpView.srcRect, tmpView.destRect, null);
        }
        canvas.restore();
    }

    @Override
    public synchronized void startAnim() {
        isRunning = true;
        mScroller.fling(0, (int) mTouchY, 0, (int) mVelocity.getYVelocity()
                , 0, 0, Integer.MAX_VALUE * -1, Integer.MAX_VALUE);
    }

    @Override
    public void scrollAnim() {
        if (mScroller.computeScrollOffset()) {
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();
            setTouchPoint(x, y);
            if (mScroller.getFinalX() == x && mScroller.getFinalY() == y) {
                isRunning = false;
                autoPageIsRunning = false;
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
        }
    }

    @Override
    public Bitmap getBgBitmap() {
        return mBgBitmap;
    }

    @Override
    public Bitmap getNextBitmap() {
        return mNextBitmap;
    }

    private static class BitmapView {
        Bitmap bitmap;
        Rect srcRect;
        Rect destRect;
        int top;
        int bottom;
    }
}
