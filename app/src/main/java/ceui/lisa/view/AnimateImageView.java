package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.utils.Common;
import de.hdodenhof.circleimageview.CircleImageView;

public class AnimateImageView extends CircleImageView {
    private Spring springX, springY;

    public AnimateImageView(Context context) {
        this(context, null);
    }

    public AnimateImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnimateImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        SpringSystem mSpringSystem = SpringSystem.create();
        springX = mSpringSystem.createSpring();
        springY = mSpringSystem.createSpring();

        springX.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                int xPos = (int) spring.getCurrentValue();
                setScreenX(xPos);

            }
        });

        springY.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                int yPos = (int) spring.getCurrentValue();
                setScreenY(yPos);
            }
        });


    }

    private void setScreenX(int screenX) {
        this.offsetLeftAndRight(screenX - getLeft());
    }

    private void setScreenY(int screenY) {
        this.offsetTopAndBottom(screenY - getTop());
    }

    public void animTo(int xPos, int yPos) {
        springX.setEndValue(xPos);
        springY.setEndValue(yPos);
    }

    /**
     * 顶部ImageView强行停止动画
     */
    public void stopAnimation() {
        springX.setAtRest();
        springY.setAtRest();
    }

    /**
     * 只为最顶部的view调用，触点松开后，回归原点
     */
    public void onRelease(int xPos, int yPos) {
        setCurrentSpringPos(getLeft(), getTop());
        animTo(xPos, yPos);
    }

    /**
     * 设置当前spring位置
     */
    public void setCurrentSpringPos(int xPos, int yPos) {
        springX.setCurrentValue(xPos);
        springY.setCurrentValue(yPos);
    }

    public Spring getSpringX() {
        return springX;
    }

    public Spring getSpringY() {
        return springY;
    }
}