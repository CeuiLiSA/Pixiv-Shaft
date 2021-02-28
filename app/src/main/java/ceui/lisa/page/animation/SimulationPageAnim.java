package ceui.lisa.page.animation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

/**
 * Created by newbiechen on 17-7-24.
 */

public class SimulationPageAnim extends HorizonPageAnim {
    private static final String TAG = "SimulationPageAnim";

    private int mCornerX = 1; // 拖拽点对应的页脚
    private int mCornerY = 1;
    private final Path mPath0;
    private final Path mPath1;

    private final PointF mBezierStart1 = new PointF(); // 贝塞尔曲线起始点
    private final PointF mBezierControl1 = new PointF(); // 贝塞尔曲线控制点
    private final PointF mBeziervertex1 = new PointF(); // 贝塞尔曲线顶点
    private PointF mBezierEnd1 = new PointF(); // 贝塞尔曲线结束点

    private final PointF mBezierStart2 = new PointF(); // 另一条贝塞尔曲线
    private final PointF mBezierControl2 = new PointF();
    private final PointF mBeziervertex2 = new PointF();
    private PointF mBezierEnd2 = new PointF();

    private float mMiddleX;
    private float mMiddleY;
    private float mDegrees;
    private float mTouchToCornerDis;
    private final ColorMatrixColorFilter mColorMatrixFilter;
    private final Matrix mMatrix;
    private final float[] mMatrixArray = {0, 0, 0, 0, 0, 0, 0, 0, 1.0f};

    private boolean mIsRTandLB; // 是否属于右上左下
    private final float mMaxLength;
    private GradientDrawable mBackShadowDrawableLR; // 有阴影的GradientDrawable
    private GradientDrawable mBackShadowDrawableRL;
    private GradientDrawable mFolderShadowDrawableLR;
    private GradientDrawable mFolderShadowDrawableRL;

    private GradientDrawable mFrontShadowDrawableHBT;
    private GradientDrawable mFrontShadowDrawableHTB;
    private GradientDrawable mFrontShadowDrawableVLR;
    private GradientDrawable mFrontShadowDrawableVRL;

    private final Paint mPaint;

    // 适配 android 高版本无法使用 XOR 的问题
    private final Path mXORPath;

    public SimulationPageAnim(int w, int h, View view, OnPageChangeListener listener) {
        super(w, h, view, listener);
        mPath0 = new Path();
        mPath1 = new Path();
        mXORPath = new Path();
        mMaxLength = (float) Math.hypot(mScreenWidth, mScreenHeight);
        mPaint = new Paint();

        mPaint.setStyle(Paint.Style.FILL);

        createDrawable();

        ColorMatrix cm = new ColorMatrix();//设置颜色数组
        float[] array = {1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 1, 0};
        cm.set(array);
        mColorMatrixFilter = new ColorMatrixColorFilter(cm);
        mMatrix = new Matrix();

        mTouchX = 0.01f; // 不让x,y为0,否则在点计算时会有问题
        mTouchY = 0.01f;
    }

    @Override
    public void drawMove(Canvas canvas) {
        switch (mDirection) {
            case NEXT:
                calcPoints();
                drawCurrentPageArea(canvas, mCurBitmap, mPath0);
                drawNextPageAreaAndShadow(canvas, mNextBitmap);
                drawCurrentPageShadow(canvas);
                drawCurrentBackArea(canvas, mCurBitmap);
                break;
            default:
                calcPoints();
                drawCurrentPageArea(canvas, mNextBitmap, mPath0);
                drawNextPageAreaAndShadow(canvas, mCurBitmap);
                drawCurrentPageShadow(canvas);
                drawCurrentBackArea(canvas, mNextBitmap);
                break;
        }
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
    public void startAnim() {
        super.startAnim();
        int dx, dy;
        // dx 水平方向滑动的距离，负值会使滚动向左滚动
        // dy 垂直方向滑动的距离，负值会使滚动向上滚动
        if (isCancel) {

            if (mCornerX > 0 && mDirection.equals(Direction.NEXT)) {
                dx = (int) (mScreenWidth - mTouchX);
            } else {
                dx = -(int) mTouchX;
            }

            if (!mDirection.equals(Direction.NEXT)) {
                dx = (int) -(mScreenWidth + mTouchX);
            }

            if (mCornerY > 0) {
                dy = (int) (mScreenHeight - mTouchY);
            } else {
                dy = -(int) mTouchY; // 防止mTouchY最终变为0
            }
        } else {
            if (mCornerX > 0 && mDirection.equals(Direction.NEXT)) {
                dx = -(int) (mScreenWidth + mTouchX);
            } else {
                dx = (int) (mScreenWidth - mTouchX + mScreenWidth);
            }
            if (mCornerY > 0) {
                dy = (int) (mScreenHeight - mTouchY);
            } else {
                dy = (int) (1 - mTouchY); // 防止mTouchY最终变为0
            }
        }
        mScroller.startScroll((int) mTouchX, (int) mTouchY, dx, dy, 400);
    }

    @Override
    public void setDirection(Direction direction) {
        super.setDirection(direction);

        switch (direction) {
            case PRE:
                //上一页滑动不出现对角
                if (mStartX > mScreenWidth / 2) {
                    calcCornerXY(mStartX, mScreenHeight);
                } else {
                    calcCornerXY(mScreenWidth - mStartX, mScreenHeight);
                }
                break;
            case NEXT:
                if (mScreenWidth / 2 > mStartX) {
                    calcCornerXY(mScreenWidth - mStartX, mStartY);
                }
                break;
        }
    }

    @Override
    public void setStartPoint(float x, float y) {
        super.setStartPoint(x, y);
        calcCornerXY(x, y);
    }

    @Override
    public void setTouchPoint(float x, float y) {
        super.setTouchPoint(x, y);
        //触摸y中间位置吧y变成屏幕高度
        if ((mStartY > mScreenHeight / 3 && mStartY < mScreenHeight * 2 / 3) || mDirection.equals(Direction.PRE)) {
            mTouchY = mScreenHeight;
        }

        if (mStartY > mScreenHeight / 3 && mStartY < mScreenHeight / 2 && mDirection.equals(Direction.NEXT)) {
            mTouchY = 1;
        }
    }

    /**
     * 创建阴影的GradientDrawable
     */
    private void createDrawable() {
        int[] color = {0x333333, 0xb0333333};
        mFolderShadowDrawableRL = new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT, color);
        mFolderShadowDrawableRL
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);

        mFolderShadowDrawableLR = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, color);
        mFolderShadowDrawableLR
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);

        // 背面颜色组
        int[] mBackShadowColors = new int[]{0xff111111, 0x111111};
        mBackShadowDrawableRL = new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT, mBackShadowColors);
        mBackShadowDrawableRL.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        mBackShadowDrawableLR = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, mBackShadowColors);
        mBackShadowDrawableLR.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        // 前面颜色组
        int[] mFrontShadowColors = new int[]{0x80111111, 0x111111};
        mFrontShadowDrawableVLR = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, mFrontShadowColors);
        mFrontShadowDrawableVLR
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
        mFrontShadowDrawableVRL = new GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT, mFrontShadowColors);
        mFrontShadowDrawableVRL
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);

        mFrontShadowDrawableHTB = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, mFrontShadowColors);
        mFrontShadowDrawableHTB
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);

        mFrontShadowDrawableHBT = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, mFrontShadowColors);
        mFrontShadowDrawableHBT
                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
    }

    /**
     * 是否能够拖动过去
     *
     * @return
     */
    public boolean canDragOver() {
        return mTouchToCornerDis > mScreenWidth / 10;
    }

    public boolean right() {
        return mCornerX <= -4;
    }

    /**
     * 绘制翻起页背面
     *
     * @param canvas
     * @param bitmap
     */
    private void drawCurrentBackArea(Canvas canvas, Bitmap bitmap) {
        int i = (int) (mBezierStart1.x + mBezierControl1.x) / 2;
        float f1 = Math.abs(i - mBezierControl1.x);
        int i1 = (int) (mBezierStart2.y + mBezierControl2.y) / 2;
        float f2 = Math.abs(i1 - mBezierControl2.y);
        float f3 = Math.min(f1, f2);
        mPath1.reset();
        mPath1.moveTo(mBeziervertex2.x, mBeziervertex2.y);
        mPath1.lineTo(mBeziervertex1.x, mBeziervertex1.y);
        mPath1.lineTo(mBezierEnd1.x, mBezierEnd1.y);
        mPath1.lineTo(mTouchX, mTouchY);
        mPath1.lineTo(mBezierEnd2.x, mBezierEnd2.y);
        mPath1.close();
        GradientDrawable mFolderShadowDrawable;
        int left;
        int right;
        if (mIsRTandLB) {
            left = (int) (mBezierStart1.x - 1);
            right = (int) (mBezierStart1.x + f3 + 1);
            mFolderShadowDrawable = mFolderShadowDrawableLR;
        } else {
            left = (int) (mBezierStart1.x - f3 - 1);
            right = (int) (mBezierStart1.x + 1);
            mFolderShadowDrawable = mFolderShadowDrawableRL;
        }
        canvas.save();
        try {
            canvas.clipPath(mPath0);
            canvas.clipPath(mPath1, Region.Op.INTERSECT);
        } catch (Exception e) {
        }

        mPaint.setColorFilter(mColorMatrixFilter);
        //对Bitmap进行取色
        int color = bitmap.getPixel(1, 1);
        //获取对应的三色
        int red = (color & 0xff0000) >> 16;
        int green = (color & 0x00ff00) >> 8;
        int blue = (color & 0x0000ff);
        //转换成含有透明度的颜色
        int tempColor = Color.argb(200, red, green, blue);


        float dis = (float) Math.hypot(mCornerX - mBezierControl1.x,
                mBezierControl2.y - mCornerY);
        float f8 = (mCornerX - mBezierControl1.x) / dis;
        float f9 = (mBezierControl2.y - mCornerY) / dis;
        mMatrixArray[0] = 1 - 2 * f9 * f9;
        mMatrixArray[1] = 2 * f8 * f9;
        mMatrixArray[3] = mMatrixArray[1];
        mMatrixArray[4] = 1 - 2 * f8 * f8;
        mMatrix.reset();
        mMatrix.setValues(mMatrixArray);
        mMatrix.preTranslate(-mBezierControl1.x, -mBezierControl1.y);
        mMatrix.postTranslate(mBezierControl1.x, mBezierControl1.y);
        canvas.drawBitmap(bitmap, mMatrix, mPaint);
        //背景叠加
        canvas.drawColor(tempColor);

        mPaint.setColorFilter(null);

        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y);
        mFolderShadowDrawable.setBounds(left, (int) mBezierStart1.y, right,
                (int) (mBezierStart1.y + mMaxLength));
        mFolderShadowDrawable.draw(canvas);
        canvas.restore();
    }

    /**
     * 绘制翻起页的阴影
     *
     * @param canvas
     */
    private void drawCurrentPageShadow(Canvas canvas) {
        double degree;
        if (mIsRTandLB) {
            degree = Math.PI
                    / 4
                    - Math.atan2(mBezierControl1.y - mTouchY, mTouchX
                    - mBezierControl1.x);
        } else {
            degree = Math.PI
                    / 4
                    - Math.atan2(mTouchY - mBezierControl1.y, mTouchX
                    - mBezierControl1.x);
        }
        // 翻起页阴影顶点与touch点的距离
        double d1 = (float) 25 * 1.414 * Math.cos(degree);
        double d2 = (float) 25 * 1.414 * Math.sin(degree);
        float x = (float) (mTouchX + d1);
        float y;
        if (mIsRTandLB) {
            y = (float) (mTouchY + d2);
        } else {
            y = (float) (mTouchY - d2);
        }
        mPath1.reset();
        mPath1.moveTo(x, y);
        mPath1.lineTo(mTouchX, mTouchY);
        mPath1.lineTo(mBezierControl1.x, mBezierControl1.y);
        mPath1.lineTo(mBezierStart1.x, mBezierStart1.y);
        mPath1.close();
        float rotateDegrees;
        canvas.save();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mXORPath.reset();
                mXORPath.moveTo(0f, 0f);
                mXORPath.lineTo(canvas.getWidth(), 0f);
                mXORPath.lineTo(canvas.getWidth(), canvas.getHeight());
                mXORPath.lineTo(0f, canvas.getHeight());
                mXORPath.close();

                // 取 path 的补集，作为 canvas 的交集
                mXORPath.op(mPath0, Path.Op.XOR);
                canvas.clipPath(mXORPath);
            } else {
                canvas.clipPath(mPath0, Region.Op.XOR);
            }
            canvas.clipPath(mPath1, Region.Op.INTERSECT);
        } catch (Exception e) {
            // TODO: handle exception
        }

        int leftx;
        int rightx;
        GradientDrawable mCurrentPageShadow;
        if (mIsRTandLB) {
            leftx = (int) (mBezierControl1.x);
            rightx = (int) mBezierControl1.x + 25;
            mCurrentPageShadow = mFrontShadowDrawableVLR;
        } else {
            leftx = (int) (mBezierControl1.x - 25);
            rightx = (int) mBezierControl1.x + 1;
            mCurrentPageShadow = mFrontShadowDrawableVRL;
        }

        rotateDegrees = (float) Math.toDegrees(Math.atan2(mTouchX
                - mBezierControl1.x, mBezierControl1.y - mTouchY));
        canvas.rotate(rotateDegrees, mBezierControl1.x, mBezierControl1.y);
        mCurrentPageShadow.setBounds(leftx,
                (int) (mBezierControl1.y - mMaxLength), rightx,
                (int) (mBezierControl1.y));
        mCurrentPageShadow.draw(canvas);
        canvas.restore();

        mPath1.reset();
        mPath1.moveTo(x, y);
        mPath1.lineTo(mTouchX, mTouchY);
        mPath1.lineTo(mBezierControl2.x, mBezierControl2.y);
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y);
        mPath1.close();
        canvas.save();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mXORPath.reset();
                mXORPath.moveTo(0f, 0f);
                mXORPath.lineTo(canvas.getWidth(), 0f);
                mXORPath.lineTo(canvas.getWidth(), canvas.getHeight());
                mXORPath.lineTo(0f, canvas.getHeight());
                mXORPath.close();

                // 取 path 的补给，作为 canvas 的交集
                mXORPath.op(mPath0, Path.Op.XOR);
                canvas.clipPath(mXORPath);
            } else {
                canvas.clipPath(mPath0, Region.Op.XOR);
            }
            canvas.clipPath(mPath1, Region.Op.INTERSECT);
        } catch (Exception e) {
        }

        if (mIsRTandLB) {
            leftx = (int) (mBezierControl2.y);
            rightx = (int) (mBezierControl2.y + 25);
            mCurrentPageShadow = mFrontShadowDrawableHTB;
        } else {
            leftx = (int) (mBezierControl2.y - 25);
            rightx = (int) (mBezierControl2.y + 1);
            mCurrentPageShadow = mFrontShadowDrawableHBT;
        }
        rotateDegrees = (float) Math.toDegrees(Math.atan2(mBezierControl2.y
                - mTouchY, mBezierControl2.x - mTouchX));
        canvas.rotate(rotateDegrees, mBezierControl2.x, mBezierControl2.y);
        float temp;
        if (mBezierControl2.y < 0)
            temp = mBezierControl2.y - mScreenHeight;
        else
            temp = mBezierControl2.y;

        int hmg = (int) Math.hypot(mBezierControl2.x, temp);
        if (hmg > mMaxLength)
            mCurrentPageShadow
                    .setBounds((int) (mBezierControl2.x - 25) - hmg, leftx,
                            (int) (mBezierControl2.x + mMaxLength) - hmg,
                            rightx);
        else
            mCurrentPageShadow.setBounds(
                    (int) (mBezierControl2.x - mMaxLength), leftx,
                    (int) (mBezierControl2.x), rightx);

        mCurrentPageShadow.draw(canvas);
        canvas.restore();
    }

    private void drawNextPageAreaAndShadow(Canvas canvas, Bitmap bitmap) {
        mPath1.reset();
        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y);
        mPath1.lineTo(mBeziervertex1.x, mBeziervertex1.y);
        mPath1.lineTo(mBeziervertex2.x, mBeziervertex2.y);
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y);
        mPath1.lineTo(mCornerX, mCornerY);
        mPath1.close();

        mDegrees = (float) Math.toDegrees(Math.atan2(mBezierControl1.x
                - mCornerX, mBezierControl2.y - mCornerY));
        int leftx;
        int rightx;
        GradientDrawable mBackShadowDrawable;
        if (mIsRTandLB) {  //左下及右上
            leftx = (int) (mBezierStart1.x);
            rightx = (int) (mBezierStart1.x + mTouchToCornerDis / 4);
            mBackShadowDrawable = mBackShadowDrawableLR;
        } else {
            leftx = (int) (mBezierStart1.x - mTouchToCornerDis / 4);
            rightx = (int) mBezierStart1.x;
            mBackShadowDrawable = mBackShadowDrawableRL;
        }
        canvas.save();
        try {
            canvas.clipPath(mPath0);
            canvas.clipPath(mPath1, Region.Op.INTERSECT);
        } catch (Exception e) {
        }


        canvas.drawBitmap(bitmap, 0, 0, null);
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y);
        mBackShadowDrawable.setBounds(leftx, (int) mBezierStart1.y, rightx,
                (int) (mMaxLength + mBezierStart1.y));//左上及右下角的xy坐标值,构成一个矩形
        mBackShadowDrawable.draw(canvas);
        canvas.restore();
    }

    private void drawCurrentPageArea(Canvas canvas, Bitmap bitmap, Path path) {
        mPath0.reset();
        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y);
        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x,
                mBezierEnd1.y);
        mPath0.lineTo(mTouchX, mTouchY);
        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y);
        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x,
                mBezierStart2.y);
        mPath0.lineTo(mCornerX, mCornerY);
        mPath0.close();

        canvas.save();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mXORPath.reset();
            mXORPath.moveTo(0f, 0f);
            mXORPath.lineTo(canvas.getWidth(), 0f);
            mXORPath.lineTo(canvas.getWidth(), canvas.getHeight());
            mXORPath.lineTo(0f, canvas.getHeight());
            mXORPath.close();

            // 取 path 的补给，作为 canvas 的交集
            mXORPath.op(path, Path.Op.XOR);
            canvas.clipPath(mXORPath);
        } else {
            canvas.clipPath(path, Region.Op.XOR);
        }
        canvas.drawBitmap(bitmap, 0, 0, null);
        try {
            canvas.restore();
        } catch (Exception e) {

        }

    }

    /**
     * 计算拖拽点对应的拖拽脚
     *
     * @param x
     * @param y
     */
    public void calcCornerXY(float x, float y) {
        if (x <= mScreenWidth / 2) {
            mCornerX = 0;
        } else {
            mCornerX = mScreenWidth;
        }
        if (y <= mScreenHeight / 2) {
            mCornerY = 0;
        } else {
            mCornerY = mScreenHeight;
        }

        mIsRTandLB = (mCornerX == 0 && mCornerY == mScreenHeight)
                || (mCornerX == mScreenWidth && mCornerY == 0);

    }

    private void calcPoints() {
        mMiddleX = (mTouchX + mCornerX) / 2;
        mMiddleY = (mTouchY + mCornerY) / 2;
        mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY)
                * (mCornerY - mMiddleY) / (mCornerX - mMiddleX);
        mBezierControl1.y = mCornerY;
        mBezierControl2.x = mCornerX;

        float f4 = mCornerY - mMiddleY;
        if (f4 == 0) {
            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
                    * (mCornerX - mMiddleX) / 0.1f;

        } else {
            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
                    * (mCornerX - mMiddleX) / (mCornerY - mMiddleY);
        }
        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x)
                / 2;
        mBezierStart1.y = mCornerY;

        // 当mBezierStart1.x < 0或者mBezierStart1.x > 480时
        // 如果继续翻页，会出现BUG故在此限制
        if (mTouchX > 0 && mTouchX < mScreenWidth) {
            if (mBezierStart1.x < 0 || mBezierStart1.x > mScreenWidth) {
                if (mBezierStart1.x < 0)
                    mBezierStart1.x = mScreenWidth - mBezierStart1.x;

                float f1 = Math.abs(mCornerX - mTouchX);
                float f2 = mScreenWidth * f1 / mBezierStart1.x;
                mTouchX = Math.abs(mCornerX - f2);

                float f3 = Math.abs(mCornerX - mTouchX)
                        * Math.abs(mCornerY - mTouchY) / f1;
                mTouchY = Math.abs(mCornerY - f3);

                mMiddleX = (mTouchX + mCornerX) / 2;
                mMiddleY = (mTouchY + mCornerY) / 2;

                mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY)
                        * (mCornerY - mMiddleY) / (mCornerX - mMiddleX);
                mBezierControl1.y = mCornerY;

                mBezierControl2.x = mCornerX;

                float f5 = mCornerY - mMiddleY;
                if (f5 == 0) {
                    mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
                            * (mCornerX - mMiddleX) / 0.1f;
                } else {
                    mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
                            * (mCornerX - mMiddleX) / (mCornerY - mMiddleY);
                }

                mBezierStart1.x = mBezierControl1.x
                        - (mCornerX - mBezierControl1.x) / 2;
            }
        }
        mBezierStart2.x = mCornerX;
        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y)
                / 2;

        mTouchToCornerDis = (float) Math.hypot((mTouchX - mCornerX),
                (mTouchY - mCornerY));

        mBezierEnd1 = getCross(new PointF(mTouchX, mTouchY), mBezierControl1, mBezierStart1,
                mBezierStart2);
        mBezierEnd2 = getCross(new PointF(mTouchX, mTouchY), mBezierControl2, mBezierStart1,
                mBezierStart2);

        mBeziervertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4;
        mBeziervertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4;
        mBeziervertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4;
        mBeziervertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4;
    }

    /**
     * 求解直线P1P2和直线P3P4的交点坐标
     *
     * @param P1
     * @param P2
     * @param P3
     * @param P4
     * @return
     */
    private PointF getCross(PointF P1, PointF P2, PointF P3, PointF P4) {
        PointF CrossP = new PointF();
        // 二元函数通式： y=ax+b
        float a1 = (P2.y - P1.y) / (P2.x - P1.x);
        float b1 = ((P1.x * P2.y) - (P2.x * P1.y)) / (P1.x - P2.x);

        float a2 = (P4.y - P3.y) / (P4.x - P3.x);
        float b2 = ((P3.x * P4.y) - (P4.x * P3.y)) / (P3.x - P4.x);
        CrossP.x = (b2 - b1) / (a1 - a2);
        CrossP.y = a1 * CrossP.x + b1;
        return CrossP;
    }
}

//
//public class SimulationPageAnim extends HorizonPageAnim {
//    private static final String TAG = "SimulationPageAnim";
//
//    private int mCornerX = 1; // 拖拽点对应的页脚
//    private int mCornerY = 1;
//    private Path mPath0;
//    private Path mPath1;
//
//    PointF mBezierStart1 = new PointF(); // 贝塞尔曲线起始点
//    PointF mBezierControl1 = new PointF(); // 贝塞尔曲线控制点
//    PointF mBeziervertex1 = new PointF(); // 贝塞尔曲线顶点
//    PointF mBezierEnd1 = new PointF(); // 贝塞尔曲线结束点
//
//    PointF mBezierStart2 = new PointF(); // 另一条贝塞尔曲线
//    PointF mBezierControl2 = new PointF();
//    PointF mBeziervertex2 = new PointF();
//    PointF mBezierEnd2 = new PointF();
//
//    float mMiddleX;
//    float mMiddleY;
//    float mDegrees;
//    float mTouchToCornerDis;
//    ColorMatrixColorFilter mColorMatrixFilter;
//    Matrix mMatrix;
//    float[] mMatrixArray = {0, 0, 0, 0, 0, 0, 0, 0, 1.0f};
//
//    boolean mIsRTandLB; // 是否属于右上左下
//    private float mMaxLength;
//    int[] mBackShadowColors;// 背面颜色组
//    int[] mFrontShadowColors;// 前面颜色组
//    GradientDrawable mBackShadowDrawableLR; // 有阴影的GradientDrawable
//    GradientDrawable mBackShadowDrawableRL;
//    GradientDrawable mFolderShadowDrawableLR;
//    GradientDrawable mFolderShadowDrawableRL;
//
//    GradientDrawable mFrontShadowDrawableHBT;
//    GradientDrawable mFrontShadowDrawableHTB;
//    GradientDrawable mFrontShadowDrawableVLR;
//    GradientDrawable mFrontShadowDrawableVRL;
//
//    Paint mPaint;
//
//    public SimulationPageAnim(int w, int h, View view, OnPageChangeListener listener) {
//        super(w, h, view, listener);
//        mPath0 = new Path();
//        mPath1 = new Path();
//        mMaxLength = (float) Math.hypot(mScreenWidth, mScreenHeight);
//        mPaint = new Paint();
//
//        mPaint.setStyle(Paint.Style.FILL);
//
//        createDrawable();
//
//        ColorMatrix cm = new ColorMatrix();//设置颜色数组
//        float array[] = {1, 0, 0, 0, 0,
//                0, 1, 0, 0, 0,
//                0, 0, 1, 0, 0,
//                0, 0, 0, 1, 0};
//        cm.set(array);
//        mColorMatrixFilter = new ColorMatrixColorFilter(cm);
//        mMatrix = new Matrix();
//
//        mTouchX = 0.01f; // 不让x,y为0,否则在点计算时会有问题
//        mTouchY = 0.01f;
//    }
//
//    @Override
//    public void drawMove(Canvas canvas) {
//        switch (mDirection) {
//            case NEXT:
//                calcPoints();
//                drawCurrentPageArea(canvas, mCurBitmap, mPath0);
//                drawNextPageAreaAndShadow(canvas, mNextBitmap);
//                drawCurrentPageShadow(canvas);
//                drawCurrentBackArea(canvas, mCurBitmap);
//                break;
//            default:
//                calcPoints();
//                drawCurrentPageArea(canvas, mNextBitmap, mPath0);
//                drawNextPageAreaAndShadow(canvas, mCurBitmap);
//                drawCurrentPageShadow(canvas);
//                drawCurrentBackArea(canvas, mNextBitmap);
//                break;
//        }
//    }
//
//    @Override
//    public void drawStatic(Canvas canvas) {
//        if (isCancel) {
//            mNextBitmap = mCurBitmap.copy(Bitmap.Config.RGB_565, true);
//            canvas.drawBitmap(mCurBitmap, 0, 0, null);
//        } else {
//            canvas.drawBitmap(mNextBitmap, 0, 0, null);
//        }
//    }
//
//    @Override
//    public void startAnim() {
//        super.startAnim();
//        int dx, dy;
//        // dx 水平方向滑动的距离，负值会使滚动向左滚动
//        // dy 垂直方向滑动的距离，负值会使滚动向上滚动
//        if (isCancel) {
//
//            if (mCornerX > 0 && mDirection.equals(Direction.NEXT)) {
//                dx = (int) (mScreenWidth - mTouchX);
//            } else {
//                dx = -(int) mTouchX;
//            }
//
//            if (!mDirection.equals(Direction.NEXT)) {
//                dx = (int) -(mScreenWidth + mTouchX);
//            }
//
//            if (mCornerY > 0) {
//                dy = (int) (mScreenHeight - mTouchY);
//            } else {
//                dy = -(int) mTouchY; // 防止mTouchY最终变为0
//            }
//        } else {
//            if (mCornerX > 0 && mDirection.equals(Direction.NEXT)) {
//                dx = -(int) (mScreenWidth + mTouchX);
//            } else {
//                dx = (int) (mScreenWidth - mTouchX + mScreenWidth);
//            }
//            if (mCornerY > 0) {
//                dy = (int) (mScreenHeight - mTouchY);
//            } else {
//                dy = (int) (1 - mTouchY); // 防止mTouchY最终变为0
//            }
//        }
//        mScroller.startScroll((int) mTouchX, (int) mTouchY, dx, dy, 400);
//    }
//
//    @Override
//    public void setDirection(Direction direction) {
//        super.setDirection(direction);
//
//        switch (direction) {
//            case PRE:
//                //上一页滑动不出现对角
//                if (mStartX > mScreenWidth / 2) {
//                    calcCornerXY(mStartX, mScreenHeight);
//                } else {
//                    calcCornerXY(mScreenWidth - mStartX, mScreenHeight);
//                }
//                break;
//            case NEXT:
//                if (mScreenWidth / 2 > mStartX) {
//                    calcCornerXY(mScreenWidth - mStartX, mStartY);
//                }
//                break;
//        }
//    }
//
//    @Override
//    public void setStartPoint(float x, float y) {
//        super.setStartPoint(x, y);
//        calcCornerXY(x, y);
//    }
//
//    @Override
//    public void setTouchPoint(float x, float y) {
//        super.setTouchPoint(x, y);
//        //触摸y中间位置吧y变成屏幕高度
//        if ((mStartY > mScreenHeight / 3 && mStartY < mScreenHeight * 2 / 3) || mDirection.equals(Direction.PRE)) {
//            mTouchY = mScreenHeight;
//        }
//
//        if (mStartY > mScreenHeight / 3 && mStartY < mScreenHeight / 2 && mDirection.equals(Direction.NEXT)) {
//            mTouchY = 1;
//        }
//    }
//
//    /**
//     * 创建阴影的GradientDrawable
//     */
//    private void createDrawable() {
//        int[] color = {0x333333, 0xb0333333};
//        mFolderShadowDrawableRL = new GradientDrawable(
//                GradientDrawable.Orientation.RIGHT_LEFT, color);
//        mFolderShadowDrawableRL
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mFolderShadowDrawableLR = new GradientDrawable(
//                GradientDrawable.Orientation.LEFT_RIGHT, color);
//        mFolderShadowDrawableLR
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mBackShadowColors = new int[]{0xff111111, 0x111111};
//        mBackShadowDrawableRL = new GradientDrawable(
//                GradientDrawable.Orientation.RIGHT_LEFT, mBackShadowColors);
//        mBackShadowDrawableRL.setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mBackShadowDrawableLR = new GradientDrawable(
//                GradientDrawable.Orientation.LEFT_RIGHT, mBackShadowColors);
//        mBackShadowDrawableLR.setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mFrontShadowColors = new int[]{0x80111111, 0x111111};
//        mFrontShadowDrawableVLR = new GradientDrawable(
//                GradientDrawable.Orientation.LEFT_RIGHT, mFrontShadowColors);
//        mFrontShadowDrawableVLR
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//        mFrontShadowDrawableVRL = new GradientDrawable(
//                GradientDrawable.Orientation.RIGHT_LEFT, mFrontShadowColors);
//        mFrontShadowDrawableVRL
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mFrontShadowDrawableHTB = new GradientDrawable(
//                GradientDrawable.Orientation.TOP_BOTTOM, mFrontShadowColors);
//        mFrontShadowDrawableHTB
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//
//        mFrontShadowDrawableHBT = new GradientDrawable(
//                GradientDrawable.Orientation.BOTTOM_TOP, mFrontShadowColors);
//        mFrontShadowDrawableHBT
//                .setGradientType(GradientDrawable.LINEAR_GRADIENT);
//    }
//
//    /**
//     * 是否能够拖动过去
//     *
//     * @return
//     */
//    public boolean canDragOver() {
//        if (mTouchToCornerDis > mScreenWidth / 10)
//            return true;
//        return false;
//    }
//
//    public boolean right() {
//        if (mCornerX > -4)
//            return false;
//        return true;
//    }
//
//    /**
//     * 绘制翻起页背面
//     *
//     * @param canvas
//     * @param bitmap
//     */
//    private void drawCurrentBackArea(Canvas canvas, Bitmap bitmap) {
//        int i = (int) (mBezierStart1.x + mBezierControl1.x) / 2;
//        float f1 = Math.abs(i - mBezierControl1.x);
//        int i1 = (int) (mBezierStart2.y + mBezierControl2.y) / 2;
//        float f2 = Math.abs(i1 - mBezierControl2.y);
//        float f3 = Math.min(f1, f2);
//        mPath1.reset();
//        mPath1.moveTo(mBeziervertex2.x, mBeziervertex2.y);
//        mPath1.lineTo(mBeziervertex1.x, mBeziervertex1.y);
//        mPath1.lineTo(mBezierEnd1.x, mBezierEnd1.y);
//        mPath1.lineTo(mTouchX, mTouchY);
//        mPath1.lineTo(mBezierEnd2.x, mBezierEnd2.y);
//        mPath1.close();
//        GradientDrawable mFolderShadowDrawable;
//        int left;
//        int right;
//        if (mIsRTandLB) {
//            left = (int) (mBezierStart1.x - 1);
//            right = (int) (mBezierStart1.x + f3 + 1);
//            mFolderShadowDrawable = mFolderShadowDrawableLR;
//        } else {
//            left = (int) (mBezierStart1.x - f3 - 1);
//            right = (int) (mBezierStart1.x + 1);
//            mFolderShadowDrawable = mFolderShadowDrawableRL;
//        }
//        canvas.save();
//        try {
//            canvas.clipPath(mPath0);
//            canvas.clipPath(mPath1, Region.Op.INTERSECT);
//        } catch (Exception e) {
//        }
//
//        mPaint.setColorFilter(mColorMatrixFilter);
//        //对Bitmap进行取色
//        int color = bitmap.getPixel(1, 1);
//        //获取对应的三色
//        int red = (color & 0xff0000) >> 16;
//        int green = (color & 0x00ff00) >> 8;
//        int blue = (color & 0x0000ff);
//        //转换成含有透明度的颜色
//        int tempColor = Color.argb(200, red, green, blue);
//
//
//        float dis = (float) Math.hypot(mCornerX - mBezierControl1.x,
//                mBezierControl2.y - mCornerY);
//        float f8 = (mCornerX - mBezierControl1.x) / dis;
//        float f9 = (mBezierControl2.y - mCornerY) / dis;
//        mMatrixArray[0] = 1 - 2 * f9 * f9;
//        mMatrixArray[1] = 2 * f8 * f9;
//        mMatrixArray[3] = mMatrixArray[1];
//        mMatrixArray[4] = 1 - 2 * f8 * f8;
//        mMatrix.reset();
//        mMatrix.setValues(mMatrixArray);
//        mMatrix.preTranslate(-mBezierControl1.x, -mBezierControl1.y);
//        mMatrix.postTranslate(mBezierControl1.x, mBezierControl1.y);
//        canvas.drawBitmap(bitmap, mMatrix, mPaint);
//        //背景叠加
//        canvas.drawColor(tempColor);
//
//        mPaint.setColorFilter(null);
//
//        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y);
//        mFolderShadowDrawable.setBounds(left, (int) mBezierStart1.y, right,
//                (int) (mBezierStart1.y + mMaxLength));
//        mFolderShadowDrawable.draw(canvas);
//        canvas.restore();
//    }
//
//    /**
//     * 绘制翻起页的阴影
//     *
//     * @param canvas
//     */
//    public void drawCurrentPageShadow(Canvas canvas) {
//        double degree;
//        if (mIsRTandLB) {
//            degree = Math.PI
//                    / 4
//                    - Math.atan2(mBezierControl1.y - mTouchY, mTouchX
//                    - mBezierControl1.x);
//        } else {
//            degree = Math.PI
//                    / 4
//                    - Math.atan2(mTouchY - mBezierControl1.y, mTouchX
//                    - mBezierControl1.x);
//        }
//        // 翻起页阴影顶点与touch点的距离
//        double d1 = (float) 25 * 1.414 * Math.cos(degree);
//        double d2 = (float) 25 * 1.414 * Math.sin(degree);
//        float x = (float) (mTouchX + d1);
//        float y;
//        if (mIsRTandLB) {
//            y = (float) (mTouchY + d2);
//        } else {
//            y = (float) (mTouchY - d2);
//        }
//        mPath1.reset();
//        mPath1.moveTo(x, y);
//        mPath1.lineTo(mTouchX, mTouchY);
//        mPath1.lineTo(mBezierControl1.x, mBezierControl1.y);
//        mPath1.lineTo(mBezierStart1.x, mBezierStart1.y);
//        mPath1.close();
//        float rotateDegrees;
//        canvas.save();
//        try {
//            canvas.clipPath(mPath0, Region.Op.XOR);
//            canvas.clipPath(mPath1, Region.Op.INTERSECT);
//        } catch (Exception e) {
//            // TODO: handle exception
//        }
//
//        int leftx;
//        int rightx;
//        GradientDrawable mCurrentPageShadow;
//        if (mIsRTandLB) {
//            leftx = (int) (mBezierControl1.x);
//            rightx = (int) mBezierControl1.x + 25;
//            mCurrentPageShadow = mFrontShadowDrawableVLR;
//        } else {
//            leftx = (int) (mBezierControl1.x - 25);
//            rightx = (int) mBezierControl1.x + 1;
//            mCurrentPageShadow = mFrontShadowDrawableVRL;
//        }
//
//        rotateDegrees = (float) Math.toDegrees(Math.atan2(mTouchX
//                - mBezierControl1.x, mBezierControl1.y - mTouchY));
//        canvas.rotate(rotateDegrees, mBezierControl1.x, mBezierControl1.y);
//        mCurrentPageShadow.setBounds(leftx,
//                (int) (mBezierControl1.y - mMaxLength), rightx,
//                (int) (mBezierControl1.y));
//        mCurrentPageShadow.draw(canvas);
//        canvas.restore();
//
//        mPath1.reset();
//        mPath1.moveTo(x, y);
//        mPath1.lineTo(mTouchX, mTouchY);
//        mPath1.lineTo(mBezierControl2.x, mBezierControl2.y);
//        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y);
//        mPath1.close();
//        canvas.save();
//        try {
//            canvas.clipPath(mPath0, Region.Op.XOR);
//            canvas.clipPath(mPath1, Region.Op.INTERSECT);
//        } catch (Exception e) {
//        }
//
//        if (mIsRTandLB) {
//            leftx = (int) (mBezierControl2.y);
//            rightx = (int) (mBezierControl2.y + 25);
//            mCurrentPageShadow = mFrontShadowDrawableHTB;
//        } else {
//            leftx = (int) (mBezierControl2.y - 25);
//            rightx = (int) (mBezierControl2.y + 1);
//            mCurrentPageShadow = mFrontShadowDrawableHBT;
//        }
//        rotateDegrees = (float) Math.toDegrees(Math.atan2(mBezierControl2.y
//                - mTouchY, mBezierControl2.x - mTouchX));
//        canvas.rotate(rotateDegrees, mBezierControl2.x, mBezierControl2.y);
//        float temp;
//        if (mBezierControl2.y < 0)
//            temp = mBezierControl2.y - mScreenHeight;
//        else
//            temp = mBezierControl2.y;
//
//        int hmg = (int) Math.hypot(mBezierControl2.x, temp);
//        if (hmg > mMaxLength)
//            mCurrentPageShadow
//                    .setBounds((int) (mBezierControl2.x - 25) - hmg, leftx,
//                            (int) (mBezierControl2.x + mMaxLength) - hmg,
//                            rightx);
//        else
//            mCurrentPageShadow.setBounds(
//                    (int) (mBezierControl2.x - mMaxLength), leftx,
//                    (int) (mBezierControl2.x), rightx);
//
//        mCurrentPageShadow.draw(canvas);
//        canvas.restore();
//    }
//
//    private void drawNextPageAreaAndShadow(Canvas canvas, Bitmap bitmap) {
//        mPath1.reset();
//        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y);
//        mPath1.lineTo(mBeziervertex1.x, mBeziervertex1.y);
//        mPath1.lineTo(mBeziervertex2.x, mBeziervertex2.y);
//        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y);
//        mPath1.lineTo(mCornerX, mCornerY);
//        mPath1.close();
//
//        mDegrees = (float) Math.toDegrees(Math.atan2(mBezierControl1.x
//                - mCornerX, mBezierControl2.y - mCornerY));
//        int leftx;
//        int rightx;
//        GradientDrawable mBackShadowDrawable;
//        if (mIsRTandLB) {  //左下及右上
//            leftx = (int) (mBezierStart1.x);
//            rightx = (int) (mBezierStart1.x + mTouchToCornerDis / 4);
//            mBackShadowDrawable = mBackShadowDrawableLR;
//        } else {
//            leftx = (int) (mBezierStart1.x - mTouchToCornerDis / 4);
//            rightx = (int) mBezierStart1.x;
//            mBackShadowDrawable = mBackShadowDrawableRL;
//        }
//        canvas.save();
//        try {
//            canvas.clipPath(mPath0);
//            canvas.clipPath(mPath1, Region.Op.INTERSECT);
//        } catch (Exception e) {
//        }
//
//
//        canvas.drawBitmap(bitmap, 0, 0, null);
//        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y);
//        mBackShadowDrawable.setBounds(leftx, (int) mBezierStart1.y, rightx,
//                (int) (mMaxLength + mBezierStart1.y));//左上及右下角的xy坐标值,构成一个矩形
//        mBackShadowDrawable.draw(canvas);
//        canvas.restore();
//    }
//
//    private void drawCurrentPageArea(Canvas canvas, Bitmap bitmap, Path path) {
//        canvas.save();
//        mPath0.reset();
//        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y);
//        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y);
//        mPath0.lineTo(mTouchX, mTouchY);
//        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y);
//        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y);
//        mPath0.lineTo(mCornerX, mCornerY);
//        mPath0.close();
//        canvas.restore();
//
//        canvas.save();
//        canvas.clipPath(path);
//        canvas.restore();
//
//////        canvas.clipPath(path, Region.Op.XOR);
////        if(Build.VERSION.SDK_INT >= 26){
//////            canvas.clipPath(path);
////            canvas.clipPath(path, Region.Op.DIFFERENCE  );
////        }else {
////            canvas.clipPath(path, Region.Op.XOR);
////        }
//
//
////        mPath0.reset();
////        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y);
////        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x,mBezierEnd1.y);
////        mPath0.lineTo(mTouchX, mTouchY);
////        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y);
////        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x,mBezierStart2.y);
////        mPath0.lineTo(mCornerX, mCornerY);
////        mPath0.close();
////
////        canvas.save();
//////        canvas.clipPath(path, Region.Op.XOR);
////        if(Build.VERSION.SDK_INT >= 26){
//////            canvas.clipPath(path);
////            canvas.clipPath(path, Region.Op.DIFFERENCE  );
////        }else {
////            canvas.clipPath(path, Region.Op.XOR);
////        }
//
//        canvas.drawBitmap(bitmap, 0, 0, null);
//        try {
//            canvas.restore();
//        } catch (Exception e) {
//
//        }
//
//    }
//
//    /**
//     * 计算拖拽点对应的拖拽脚
//     *
//     * @param x
//     * @param y
//     */
//    public void calcCornerXY(float x, float y) {
//        if (x <= mScreenWidth / 2) {
//            mCornerX = 0;
//        } else {
//            mCornerX = mScreenWidth;
//        }
//        if (y <= mScreenHeight / 2) {
//            mCornerY = 0;
//        } else {
//            mCornerY = mScreenHeight;
//        }
//
//        if ((mCornerX == 0 && mCornerY == mScreenHeight)
//                || (mCornerX == mScreenWidth && mCornerY == 0)) {
//            mIsRTandLB = true;
//        } else {
//            mIsRTandLB = false;
//        }
//
//    }
//
//    private void calcPoints() {
//        mMiddleX = (mTouchX + mCornerX) / 2;
//        mMiddleY = (mTouchY + mCornerY) / 2;
//        mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY)
//                * (mCornerY - mMiddleY) / (mCornerX - mMiddleX);
//        mBezierControl1.y = mCornerY;
//        mBezierControl2.x = mCornerX;
//
//        float f4 = mCornerY - mMiddleY;
//        if (f4 == 0) {
//            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
//                    * (mCornerX - mMiddleX) / 0.1f;
//
//        } else {
//            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
//                    * (mCornerX - mMiddleX) / (mCornerY - mMiddleY);
//        }
//        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x)
//                / 2;
//        mBezierStart1.y = mCornerY;
//
//        // 当mBezierStart1.x < 0或者mBezierStart1.x > 480时
//        // 如果继续翻页，会出现BUG故在此限制
//        if (mTouchX > 0 && mTouchX < mScreenWidth) {
//            if (mBezierStart1.x < 0 || mBezierStart1.x > mScreenWidth) {
//                if (mBezierStart1.x < 0)
//                    mBezierStart1.x = mScreenWidth - mBezierStart1.x;
//
//                float f1 = Math.abs(mCornerX - mTouchX);
//                float f2 = mScreenWidth * f1 / mBezierStart1.x;
//                mTouchX = Math.abs(mCornerX - f2);
//
//                float f3 = Math.abs(mCornerX - mTouchX)
//                        * Math.abs(mCornerY - mTouchY) / f1;
//                mTouchY = Math.abs(mCornerY - f3);
//
//                mMiddleX = (mTouchX + mCornerX) / 2;
//                mMiddleY = (mTouchY + mCornerY) / 2;
//
//                mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY)
//                        * (mCornerY - mMiddleY) / (mCornerX - mMiddleX);
//                mBezierControl1.y = mCornerY;
//
//                mBezierControl2.x = mCornerX;
//
//                float f5 = mCornerY - mMiddleY;
//                if (f5 == 0) {
//                    mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
//                            * (mCornerX - mMiddleX) / 0.1f;
//                } else {
//                    mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX)
//                            * (mCornerX - mMiddleX) / (mCornerY - mMiddleY);
//                }
//
//                mBezierStart1.x = mBezierControl1.x
//                        - (mCornerX - mBezierControl1.x) / 2;
//            }
//        }
//        mBezierStart2.x = mCornerX;
//        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y)
//                / 2;
//
//        mTouchToCornerDis = (float) Math.hypot((mTouchX - mCornerX),
//                (mTouchY - mCornerY));
//
//        mBezierEnd1 = getCross(new PointF(mTouchX, mTouchY), mBezierControl1, mBezierStart1,
//                mBezierStart2);
//        mBezierEnd2 = getCross(new PointF(mTouchX, mTouchY), mBezierControl2, mBezierStart1,
//                mBezierStart2);
//
//        mBeziervertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4;
//        mBeziervertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4;
//        mBeziervertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4;
//        mBeziervertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4;
//    }
//
//    /**
//     * 求解直线P1P2和直线P3P4的交点坐标
//     *
//     * @param P1
//     * @param P2
//     * @param P3
//     * @param P4
//     * @return
//     */
//    public PointF getCross(PointF P1, PointF P2, PointF P3, PointF P4) {
//        PointF CrossP = new PointF();
//        // 二元函数通式： y=ax+b
//        float a1 = (P2.y - P1.y) / (P2.x - P1.x);
//        float b1 = ((P1.x * P2.y) - (P2.x * P1.y)) / (P1.x - P2.x);
//
//        float a2 = (P4.y - P3.y) / (P4.x - P3.x);
//        float b2 = ((P3.x * P4.y) - (P4.x * P3.y)) / (P3.x - P4.x);
//        CrossP.x = (b2 - b1) / (a1 - a2);
//        CrossP.y = a1 * CrossP.x + b1;
//        return CrossP;
//    }
//}
