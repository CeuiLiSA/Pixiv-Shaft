package ceui.lisa.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * Backport of the full-circle seam fix from Material Components PR #5028.
 *
 * <p>Material 1.14 draws a complete rounded track with {@code drawArc()}, so its two caps overlap
 * at 0 degrees. This view draws complete circles with {@code drawOval()} and leaves partial active
 * indicators to Material. Remove it after a Material release includes
 * https://github.com/material-components/material-components-android/pull/5028.
 */
public class SeamlessCircularProgressIndicator extends CircularProgressIndicator {

    private final Paint fullCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fullCircleBounds = new RectF();

    @ColorInt
    private int seamlessTrackColor;
    private boolean seamlessTrackInitialized;
    private boolean indeterminateTrackVisible;

    public SeamlessCircularProgressIndicator(@NonNull Context context) {
        this(context, null);
    }

    public SeamlessCircularProgressIndicator(
            @NonNull Context context,
            @Nullable AttributeSet attrs) {
        this(context, attrs,
                com.google.android.material.R.attr.circularProgressIndicatorStyle);
    }

    public SeamlessCircularProgressIndicator(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray attributes = getContext().obtainStyledAttributes(
                attrs,
                com.google.android.material.R.styleable.CircularProgressIndicator,
                defStyleAttr,
                CircularProgressIndicator.DEF_STYLE_RES);
        indeterminateTrackVisible = attributes.getBoolean(
                com.google.android.material.R.styleable
                        .CircularProgressIndicator_indeterminateTrackVisible,
                true);
        attributes.recycle();

        seamlessTrackColor = super.getTrackColor();
        seamlessTrackInitialized = true;
        syncMaterialTrackColor();
    }

    @Override
    public int getTrackColor() {
        return seamlessTrackInitialized ? seamlessTrackColor : super.getTrackColor();
    }

    @Override
    public void setTrackColor(@ColorInt int trackColor) {
        if (!seamlessTrackInitialized) {
            super.setTrackColor(trackColor);
            return;
        }
        seamlessTrackColor = trackColor;
        syncMaterialTrackColor();
        invalidate();
    }

    @Override
    protected synchronized void onDraw(@NonNull Canvas canvas) {
        boolean replaceFullTrack = shouldReplaceFullTrack();
        syncMaterialTrackColor();

        if (replaceFullTrack) {
            drawFullCircle(canvas, seamlessTrackColor);
        }

        // The upstream patch also uses drawOval() when the active indicator reaches a full circle.
        if (!isIndeterminate()
                && !hasDeterminateWave()
                && getMax() > 0
                && getProgress() >= getMax()) {
            int[] indicatorColors = getIndicatorColor();
            if (indicatorColors.length > 0) {
                drawFullCircle(canvas, indicatorColors[0]);
            }
            return;
        }

        // With a non-zero gap, Material normally draws a partial track. At 0% it becomes a full
        // track again, so handle just that frame with drawOval() and skip its seam-prone drawArc().
        if (!isIndeterminate() && getProgress() <= 0) {
            if (!replaceFullTrack) {
                drawFullCircle(canvas, seamlessTrackColor);
            }
            return;
        }

        // When replacing a full track, Material's internal track is transparent and it draws only
        // the partial active indicator. Otherwise its original partial-track behavior is preserved.
        super.onDraw(canvas);
    }

    private boolean shouldReplaceFullTrack() {
        if (getIndicatorTrackGapSize() != 0) {
            return false;
        }
        if (!isIndeterminate()) {
            return true;
        }
        boolean hasIndeterminateWave =
                getWaveAmplitude() > 0 && getWavelengthIndeterminate() > 0;
        return indeterminateTrackVisible && !hasIndeterminateWave;
    }

    private boolean hasDeterminateWave() {
        return getWaveAmplitude() > 0 && getWavelengthDeterminate() > 0;
    }

    private void syncMaterialTrackColor() {
        int materialTrackColor = shouldReplaceFullTrack()
                ? Color.TRANSPARENT
                : seamlessTrackColor;
        if (super.getTrackColor() != materialTrackColor) {
            super.setTrackColor(materialTrackColor);
        }
    }

    private void drawFullCircle(@NonNull Canvas canvas, @ColorInt int color) {
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float intrinsicSize = getIndicatorSize() + getIndicatorInset() * 2f;
        if (contentWidth <= 0f || contentHeight <= 0f || intrinsicSize <= 0f) {
            return;
        }

        float radius = (getIndicatorSize() - getTrackThickness()) / 2f;
        if (radius <= 0f) {
            return;
        }

        int saveCount = canvas.save();
        canvas.translate(
                getPaddingLeft() + contentWidth / 2f,
                getPaddingTop() + contentHeight / 2f);
        canvas.scale(contentWidth / intrinsicSize, contentHeight / intrinsicSize);

        fullCirclePaint.setStyle(Paint.Style.STROKE);
        fullCirclePaint.setStrokeWidth(getTrackThickness());
        fullCirclePaint.setStrokeCap(Paint.Cap.BUTT);
        fullCirclePaint.setColor(color);
        fullCircleBounds.set(-radius, -radius, radius, radius);
        canvas.drawOval(fullCircleBounds, fullCirclePaint);
        canvas.restoreToCount(saveCount);
    }
}
