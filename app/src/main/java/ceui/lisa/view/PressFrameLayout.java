package ceui.lisa.view;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import ceui.lisa.utils.DensityUtil;

public class PressFrameLayout extends RelativeLayout {
    private int width = 0;//父布局宽度
    private int height = 0;//父布局高度
    private final int padding;//为阴影和按压变形预留位置
    private final int cornerRadius;//控件圆角
    private final float shadeOffset;//阴影偏移
    Paint paintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    Camera camera = new Camera();
    float cameraX = 0f;//触摸点x轴方向偏移比例
    float cameraY = 0f;//触摸点y轴方向偏移比例
    private int colorBg;//背景色
    private final int shadeAlpha = 0xaa000000;//背景阴影透明度

    private float touchProgress = 1f;//按压缩放动画控制
    private float cameraProgress = 0f;//相机旋转（按压偏移）动画控制
    TouchArea pressArea = new TouchArea(0,0,0,0);//按压效果区域

    boolean isInPressArea = true;//按压位置是在内圈还是外圈
    private final int maxAngle = 5;//倾斜时的相机最大倾斜角度，deg
    private final float scale = 0.98f;//整体按压时的形变控制

    private long pressTime = 0;//计算按压时间，小于500毫秒响应onClick()
    Bitmap bitmap;//background为图片时
    Rect srcRectF = new Rect();
    RectF dstRectF = new RectF();

    public PressFrameLayout(Context context) {
        super(context);
    }

    public PressFrameLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PressFrameLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        //开启viewGroup的onDraw()
        setWillNotDraw(false);

        padding = new DensityUtil().dip2px(20);
        cornerRadius = new DensityUtil().dip2px(5);
        shadeOffset = new DensityUtil().dip2px(5);

        //View的background为颜色或者图片的两种情况
        Drawable background = getBackground();
        if (background instanceof ColorDrawable) {
            colorBg = ((ColorDrawable) background).getColor();
            paintBg.setColor(colorBg);
        } else if(background instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) background).getBitmap();
            srcRectF = new Rect(0,0,bitmap.getWidth(),bitmap.getHeight());
        }
        setBackground(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isInPressArea) {
            camera.save();
            //相机在控件中心上方，在x，y轴方向旋转，形成控件倾斜效果
            camera.rotateX(maxAngle*cameraX*cameraProgress);
            camera.rotateY(maxAngle*cameraY*cameraProgress);
            canvas.translate(width/2f, height/2f);
            camera.applyToCanvas(canvas);
            //还原canvas坐标系
            canvas.translate(-width/2f, -height/2f);
            camera.restore();
        }
        //绘制阴影和背景
        paintBg.setShadowLayer(shadeOffset*touchProgress,0,0,(colorBg & 0x00FFFFFF) | shadeAlpha);
        if (bitmap!=null){
            canvas.drawBitmap(bitmap,srcRectF,dstRectF,paintBg);
        }else {
            canvas.drawRoundRect(dstRectF
                    ,cornerRadius,cornerRadius,paintBg);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        width = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);

        dstRectF.set(padding,padding,width-padding,height-padding);
        //计算输入按压的内部范围,布局中心部分为内圈，其他为外圈
        pressArea.set((width-2*padding)/4f + padding,(height-2*padding)/4f + padding
                ,width-(width-2*padding)/4f - padding,height-(width-2*padding)/4f - padding);
    }

    /**
     * 判断是按压内圈还是外圈
     * @return true:按压内圈；false:按压外圈
     */
    private boolean isInPressArea(float x, float y){
        return x > pressArea.getLeft() && x < pressArea.getRight()
                && y >pressArea.getTop() && y < pressArea.getBottom();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        AnimatorSet animatorSet = new AnimatorSet();
        int duration = 100;//按压动画时长
        int type = 0;
        switch (event.getAction()){
            case MotionEvent.ACTION_DOWN:
                pressTime = System.currentTimeMillis();
                type = 1;
                isInPressArea = isInPressArea(event.getX(),event.getY());
                break;
            case MotionEvent.ACTION_CANCEL:
                type = 2;
                break;
            case MotionEvent.ACTION_UP:
                if((System.currentTimeMillis()-pressTime) < 500){
                    performClick();
                }
                type = 2;
                break;
        }
        if (isInPressArea){//内圈按压效果
            if (type !=0){
                ObjectAnimator animX = ObjectAnimator.ofFloat(this,"scaleX"
                        ,type==1?1:scale,type==1?scale:1).setDuration(duration);
                ObjectAnimator animY = ObjectAnimator.ofFloat(this,"scaleY"
                        ,type==1?1:scale,type==1?scale:1).setDuration(duration);
                ObjectAnimator animZ = ObjectAnimator.ofFloat(this,"touchProgress"
                        ,type==1?1:0,type==1?0:1).setDuration(duration);
                animX.setInterpolator(new DecelerateInterpolator());
                animY.setInterpolator(new DecelerateInterpolator());
                animZ.setInterpolator(new DecelerateInterpolator());
                animatorSet.playTogether(animX,animY,animZ);
                animatorSet.start();
            }
        }else {//外圈按压效果
            cameraX = (event.getX() - width / 2f) / ((width-2*padding)/2f);
            if (cameraX > 1) cameraX = 1;
            if (cameraX < -1) cameraX = -1;

            cameraY = (event.getY() - height / 2f) / ((height-2*padding)/2f);
            if (cameraY > 1) cameraY = 1;
            if (cameraY < -1) cameraY = -1;
            //坐标系调整
            float tmp = cameraX;
            cameraX = -cameraY;
            cameraY = tmp;
            switch (type) {
                case 1://按下动画
                    ObjectAnimator.ofFloat(this,"cameraProgress"
                            ,0,1).setDuration(duration).start();
                    break;
                case 2://还原动画
                    ObjectAnimator.ofFloat(this,"cameraProgress"
                            ,1,0).setDuration(duration).start();
                    break;
                default:
                    break;
            }
            invalidate();
        }
        return true;
    }

    public float getTouchProgress() {
        return touchProgress;
    }

    public void setTouchProgress(float touchProgress) {
        this.touchProgress = touchProgress;
        invalidate();
    }

    public float getCameraProgress() {
        return cameraProgress;
    }

    public void setCameraProgress(float cameraProgress) {
        this.cameraProgress = cameraProgress;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
