package ceui.lisa.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.HashMap;

import ceui.lisa.utils.Common;

public class SilverLinkView extends View implements GestureDetector.OnGestureListener {

    private int radius;
    private int circles;
    private Context mContext;
    private Canvas mCanvas;
    private float centerX, centerY;
    private HashMap<Integer, Integer> mHashMap = new HashMap<>();
    private GestureDetector mGestureDetector;
    private Paint mPaint;
    private final int[] colors = new int[]{
            0xFFd50000,
            0xFFc51162,
            0xFFaa00ff,
            0xFF6200ea,
            0xFF304ffe,
            0xFF2962ff,
            0xFF0091ea,
            0xFF00b8d4,
            0xFF00bfa5,
            0xFF00c853,
            0xFF64dd17,
            0xFFaeea00,
            0xFFffd600,
            0xFFdd2c00,
            0xFFe8eaf6,
            0xFFc5cae9,
            0xFF9fa8da,
            0xFF7986cb,
            0xFF5c6bc0,
            0xFF3f51b5,
            0xFF3949ab,
            0xFF303f9f,
            0xFF283593,
            0xFF1a237e,
            0xFF8c9eff,
            0xFF536dfe,
            0xFF3d5afe,
            0xFF304ffe,
            0xFFb39ddb,
            0xFF9575cd,
            0xFF7e57c2,
            0xFF673ab7,
            0xFF5e35b1,
            0xFF512da8,
            0xFF4527a0,
            0xFF311b92
    };

    public SilverLinkView(Context context) {
        super(context);
        init(context);
    }

    public SilverLinkView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SilverLinkView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public SilverLinkView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        mContext = context;
        mGestureDetector = new GestureDetector(mContext, SilverLinkView.this);
        mPaint = new Paint();
        mPaint.setColor(colors[0]);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setStrokeWidth(8);
        mPaint.setAntiAlias(true);
        mHashMap.put(0, colors[0]);

    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        centerX = event.getX();
        centerY = event.getY();
        return mGestureDetector.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final double sq = Math.sqrt(
                getMeasuredWidth() * getMeasuredWidth() +
                        getMeasuredHeight() * getMeasuredHeight()
        );

        int lastColor = 0;
        for (int i = 0; i < circles; i++) {

            //设置半径
            int r;
            if (circles == 1) {
                r = radius;
            } else {
                r = radius - i * 100;
            }

            //如果半径小于屏幕对角线，绘制
            if (r < sq) {
                Integer color = mHashMap.get(i);
                if (color == null) {
                    do {
                        color = colors[Common.flatRandom(0, colors.length)];
                    } while (color == lastColor);
                    mHashMap.put(i, color);
                }

                mPaint.setColor(color);
                lastColor = color;

                canvas.drawCircle(
                        centerX,
                        centerY,
                        r, mPaint);
            }
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        Common.showLog("SilverLinkView onDown ");
//        radius = 0;
//        circles = 1;
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {
        Common.showLog("SilverLinkView onShowPress ");
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        Common.showLog("SilverLinkView onSingleTapUp ");
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        Common.showLog("SilverLinkView onScroll ");
        radius = radius + 8;
        float ratio = radius / 100.0f;
        if (ratio < 1) {
            circles = 1;
        } else {
            circles = (int) ratio;
        }
        invalidate();
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        Common.showLog("SilverLinkView onLongPress ");

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        Common.showLog("SilverLinkView onFling ");
        return false;
    }
}
