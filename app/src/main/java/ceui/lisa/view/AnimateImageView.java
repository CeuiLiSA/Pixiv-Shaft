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

    public AnimateImageView getNext() {
        return next;
    }

    public void setNext(AnimateImageView next) {
        this.next = next;
    }

    private AnimateImageView next;
    // 此为跟踪的回调，当前面一个view移动的时候，此为后面的view，需要更新endValue

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
        int temp = screenX - getLeft();
        if (next != null) {
            Common.showLog("offsetLeftAndRight " + temp);
        }
        offsetLeftAndRight(temp);
    }

    private void setScreenY(int screenY) {
        int temp = screenY - getTop();
        if (next != null) {
            Common.showLog("offsetTopAndBottom " + temp);
        }
        offsetTopAndBottom(temp);
    }

    public void animTo(int xPos, int yPos) {
        springX.setEndValue(xPos);
        springY.setEndValue(yPos);
        if (next != null) {
            next.animTo(xPos, yPos);
        }
    }

    public void goTo(int xPos, int yPos) {
        setScreenX(xPos);
        setScreenY(yPos);
        if (next != null) {
            next.animTo(xPos, yPos);
        }
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