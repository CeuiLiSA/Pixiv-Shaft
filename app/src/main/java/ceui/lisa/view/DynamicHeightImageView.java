package ceui.lisa.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DynamicHeightImageView extends androidx.appcompat.widget.AppCompatImageView {

    private float mHeightRatio;
    private ScaleType tmpScaleType;

    /** 非 null = 全景模式(见 {@link #setPanorama}),图按内容区高度等比放大、按 {@link PanoramaPan#fraction} 横向偏移。 */
    @Nullable
    private PanoramaPan panorama;
    /** 进全景前的 scaleType,退出时还原。 */
    @Nullable
    private ScaleType scaleTypeBeforePanorama;
    private final Matrix panoramaMatrix = new Matrix();

    public DynamicHeightImageView(Context context) {
        super(context);
    }

    public DynamicHeightImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DynamicHeightImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setHeightRatio(float ratio) {
        if (ratio != mHeightRatio) {
            mHeightRatio = ratio;
            requestLayout();
        }
    }

    public void setHeightRatioAndScaleType(float ratio, ScaleType scaleType) {
        boolean b1 = ratio != mHeightRatio;
        if (b1) {
            mHeightRatio = ratio;
            requestLayout();
        }
        tmpScaleType = scaleType;
    }

    /**
     * 全景模式:超宽图(自然高只有一条缝)不再按宽度缩,而是把图放大到内容区高度、横向可拖。
     * 传 null 退出,scaleType 还原到进入前的值。同一个 {@link PanoramaPan} 可以挂在多层 ImageView 上
     * (底层 large + 顶层原图 overlay),偏移共享,拖动时两层同步。
     */
    public void setPanorama(@Nullable PanoramaPan pan) {
        if (panorama == pan) {
            return;
        }
        if (pan != null && panorama == null) {
            scaleTypeBeforePanorama = getScaleType();
            setScaleType(ScaleType.MATRIX);
        } else if (pan == null && scaleTypeBeforePanorama != null) {
            setScaleType(scaleTypeBeforePanorama);
            scaleTypeBeforePanorama = null;
        }
        panorama = pan;
        applyPanoramaMatrix();
    }

    public boolean isPanorama() {
        return panorama != null;
    }

    /** 全景下图比内容区宽出的像素数;不在全景 / 图未到 / 图不比盒宽时为 0(= 没什么可拖)。 */
    public float getPanoramaMaxShift() {
        if (panorama == null) {
            return 0f;
        }
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) {
            return 0f;
        }
        int contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) {
            return 0f;
        }
        float scale = (float) contentH / d.getIntrinsicHeight();
        return Math.max(0f, d.getIntrinsicWidth() * scale - contentW);
    }

    /** 偏移变了(拖动)/图到了/尺寸变了都从这里重算矩阵。 */
    public void applyPanoramaMatrix() {
        PanoramaPan pan = panorama;
        if (pan == null) {
            return;
        }
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) {
            return;
        }
        int contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) {
            return;
        }
        float scale = (float) contentH / d.getIntrinsicHeight();
        float shift = Math.max(0f, d.getIntrinsicWidth() * scale - contentW);
        panoramaMatrix.setScale(scale, scale);
        panoramaMatrix.postTranslate(-shift * pan.getFraction(), 0f);
        setImageMatrix(panoramaMatrix);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        applyPanoramaMatrix();
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        if (changed) {
            applyPanoramaMatrix();
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mHeightRatio > 0.0) {
            // set the image views size
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = (int) (width * mHeightRatio);
            setMeasuredDimension(width, height);
            if(tmpScaleType != null && tmpScaleType != getScaleType()){
                setScaleType(tmpScaleType);
            }
        }
        else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
