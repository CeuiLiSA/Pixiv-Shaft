package ceui.lisa.page.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.widget.Scroller;

/**
 * Created by Administrator on 2016/8/1 0001.
 */
public abstract class AnimationProvider {

    protected Bitmap mCurPageBitmap, mNextPageBitmap;
    protected float myStartX;
    protected float myStartY;
    protected int myEndX;
    protected int myEndY;
    protected Direction myDirection;
    protected int mScreenWidth;
    protected int mScreenHeight;
    protected PointF mTouch = new PointF(); // 拖拽点
    private Direction direction = Direction.NONE;
    private boolean isCancel = false;
    public AnimationProvider(int width, int height) {
        mCurPageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        mNextPageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

        this.mScreenWidth = width;
        this.mScreenHeight = height;
    }

    //绘制滑动页面
    public abstract void drawMove(Canvas canvas);

    //绘制不滑动页面
    public abstract void drawStatic(Canvas canvas);

    //设置开始拖拽点
    public void setStartPoint(float x, float y) {
        myStartX = x;
        myStartY = y;
    }

    //设置拖拽点
    public void setTouchPoint(float x, float y) {
        mTouch.x = x;
        mTouch.y = y;
    }

    public Direction getDirection() {
        return direction;
    }

    //设置方向
    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public abstract void startAnimation(Scroller scroller);

    /**
     * 转换页面，在显示下一章的时候，必须首先调用此方法
     */
    public void changePage() {
        Bitmap bitmap = mCurPageBitmap;
        mCurPageBitmap = mNextPageBitmap;
        mNextPageBitmap = bitmap;
    }

    public Bitmap getNextBitmap() {
        return mNextPageBitmap;
    }

    public Bitmap getBgBitmap() {
        return mNextPageBitmap;
    }

    public boolean getCancel() {
        return isCancel;
    }

    public void setCancel(boolean isCancel) {
        this.isCancel = isCancel;
    }

    public enum Direction {
        NONE(true), NEXT(true), PRE(true), UP(false), DOWN(false);

        public final boolean isHorizontal;

        Direction(boolean isHorizontal) {
            this.isHorizontal = isHorizontal;
        }
    }
}
