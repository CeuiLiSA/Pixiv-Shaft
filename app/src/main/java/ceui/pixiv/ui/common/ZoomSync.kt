package ceui.pixiv.ui.common

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.panpf.zoomimage.SketchZoomImageView
import com.github.panpf.zoomimage.util.IntSizeCompat
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import com.github.panpf.zoomimage.util.TransformCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 把两张 SketchZoomImageView 的缩放/位移做单向同步：
 * 当前触控流先落在哪张图，哪张就是 source；被动图上的真实触控直到抬手都忽略。
 *
 * 同步的是 **userTransform**(用户手势相对 fit 的倍率/位移),不是 final
 * transform。两张图尺寸不一样(典型例子:超分对比,原图 2400x3200、上采样
 * 4800x6400)时 baseScale 也不一样,直接同步 final scale 会让 follower 在视
 * 觉上比 source 多/少缩放——top 双击到 2x base 时,bottom 把同一个 final
 * scale 解释成 4x base,两边对不上(issue #882 后续报告)。
 *
 * 双指捏合时把触摸序列转发给 follower，让两张图都走库的橡皮筋回弹；
 * 其他变换继续按 userTransform 镜像。
 *
 * 而双向 syncing-flag 又会在 min/max scale 不一致时反复 clamp 把 UI 喂死
 * (issue #882),所以仍然走 source-follower 单向。
 */
@SuppressLint("ClickableViewAccessibility")
fun setupZoomSync(
    lifecycleOwner: LifecycleOwner,
    left: SketchZoomImageView,
    right: SketchZoomImageView,
) {
    var source: SketchZoomImageView? = null
    var activeTouch: SketchZoomImageView? = null
    var ignoredTouch: SketchZoomImageView? = null
    var mirroringPinch = false
    var pinchRollbackPending = false
    fun forwardTouch(dst: SketchZoomImageView, event: MotionEvent) {
        MotionEvent.obtain(event).also {
            dst.onTouchEvent(it) // Bypass OnTouchListener so the follower never becomes source.
            it.recycle()
        }
    }

    val tracker = android.view.View.OnTouchListener { v, event ->
        val touched = v as SketchZoomImageView
        val action = event.actionMasked
        if (touched === ignoredTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoredTouch = null
            return@OnTouchListener true
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (activeTouch != null && activeTouch !== touched) {
                ignoredTouch = touched
                return@OnTouchListener true
            }
            activeTouch = touched
        } else if (activeTouch !== touched) {
            return@OnTouchListener true
        }
        val follower = if (touched === left) right else left
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                source = touched
                mirroringPinch = false
                pinchRollbackPending = false
                // Keep both gesture detectors' drag and tap history identical before a pinch.
                forwardTouch(follower, event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mirroringPinch = true
                forwardTouch(follower, event)
            }

            else -> {
                forwardTouch(follower, event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pinchRollbackPending = mirroringPinch
                    mirroringPinch = false
                    activeTouch = null
                }
            }
        }
        false
    }
    left.setOnTouchListener(tracker)
    right.setOnTouchListener(tracker)

    // Original dimensions can arrive after the preview; keep both rubber-band thresholds aligned.
    lifecycleOwner.lifecycleScope.launch {
        combine(
            left.zoomable.contentSizeState, right.zoomable.contentSizeState,
            left.zoomable.containerSizeState, right.zoomable.containerSizeState,
            left.zoomable.contentOriginSizeState, right.zoomable.contentOriginSizeState,
        ) { sizes ->
            sizes.take(4).all {
                it.width > 0 && it.height > 0
            }
        }.collect { ready ->
            if (!ready) return@collect
            kotlinx.coroutines.yield()
            val leftBase = left.zoomable.baseTransformState.value.scaleX
            val rightBase = right.zoomable.baseTransformState.value.scaleX
            val leftMax = left.zoomable.maxScaleState.value / leftBase
            val rightMax = right.zoomable.maxScaleState.value / rightBase
            if (leftMax < rightMax) ensureFollowerZoomRange(right, left, rightBase, leftBase)
            else if (rightMax < leftMax) ensureFollowerZoomRange(left, right, leftBase, rightBase)
        }
    }

    fun shouldMirror(active: SketchZoomImageView, transform: TransformCompat): Boolean {
        if (source !== active || mirroringPinch) return false
        if (pinchRollbackPending) {
            if (transform.scaleX > active.zoomable.maxScaleState.value + 0.001f) return false
            pinchRollbackPending = false
        }
        return true
    }

    lifecycleOwner.lifecycleScope.launch {
        left.zoomable.transformState.collect { transform ->
            if (!shouldMirror(left, transform)) return@collect
            mirrorUserTransform(src = left, dst = right, srcTransform = transform)
        }
    }
    lifecycleOwner.lifecycleScope.launch {
        right.zoomable.transformState.collect { transform ->
            if (!shouldMirror(right, transform)) return@collect
            mirrorUserTransform(src = right, dst = left, srcTransform = transform)
        }
    }
}

/**
 * 把 src 的 userTransform 镜像到 dst:
 *   userScale_src  = transform.scaleX / baseScale_src
 *   userOffset_src = transform.offset - baseOffset_src * userScale_src
 * 想要 dst 的 userScale/userOffset 与 src 一致, 反推出要传给 dst.scale/offset
 * 的 final 值(API 接收 final, 内部再除回 dst 自己的 baseScale)。
 */
private suspend fun mirrorUserTransform(
    src: SketchZoomImageView,
    dst: SketchZoomImageView,
    srcTransform: TransformCompat,
) {
    val srcBase = src.zoomable.baseTransformState.value
    val dstBase = dst.zoomable.baseTransformState.value
    val srcBaseScale = srcBase.scaleX
    val dstBaseScale = dstBase.scaleX
    if (srcBaseScale <= 0f || dstBaseScale <= 0f) return

    val userScale = srcTransform.scaleX / srcBaseScale
    val userOffsetX = srcTransform.offset.x - srcBase.offset.x * userScale
    val userOffsetY = srcTransform.offset.y - srcBase.offset.y * userScale

    ensureFollowerZoomRange(src, dst, srcBaseScale, dstBaseScale)
    val dstFinalScale = userScale * dstBaseScale
    val dstFinalOffset = OffsetCompat(
        x = userOffsetX + dstBase.offset.x * userScale,
        y = userOffsetY + dstBase.offset.y * userScale,
    )

    dst.zoomable.scale(dstFinalScale, animated = false)
    dst.zoomable.offset(dstFinalOffset, animated = false)
}

/** 让跟随图至少支持操控图的相对最大倍率，避免高分辨率图继续放大时另一张先被截断。 */
private suspend fun ensureFollowerZoomRange(
    src: SketchZoomImageView,
    dst: SketchZoomImageView,
    srcBaseScale: Float,
    dstBaseScale: Float,
) {
    val sourceMaxUserScale = src.zoomable.maxScaleState.value / srcBaseScale
    val followerMaxUserScale = dst.zoomable.maxScaleState.value / dstBaseScale
    val calculator = dst.zoomable.scalesCalculatorState.value
    val needsUpdate = if (calculator is MinimumUserMaxScalesCalculator) {
        kotlin.math.abs(calculator.minimumUserMaxScale - sourceMaxUserScale) > 0.001f
    } else {
        sourceMaxUserScale > followerMaxUserScale + 0.001f
    }
    if (!needsUpdate) return

    // Changing the calculator resets ZoomImage's transform, even during an active gesture.
    val transform = dst.zoomable.transformState.value
    dst.zoomable.setScalesCalculator(MinimumUserMaxScalesCalculator(sourceMaxUserScale))
    dst.zoomable.scale(transform.scaleX, animated = false)
    dst.zoomable.offset(transform.offset, animated = false)
}

private data class MinimumUserMaxScalesCalculator(
    val minimumUserMaxScale: Float,
) : ScalesCalculator {
    override fun calculateWithBase(
        containerSize: IntSizeCompat,
        contentSize: IntSizeCompat,
        contentOriginSize: IntSizeCompat,
        contentScale: ContentScaleCompat,
        baseScale: Float,
        initialScale: Float,
    ): ScalesCalculator.Result {
        val result = ScalesCalculator.Dynamic.calculateWithBase(
            containerSize, contentSize, contentOriginSize, contentScale, baseScale, initialScale,
        )
        return result.copy(maxScale = maxOf(result.maxScale, baseScale * this.minimumUserMaxScale))
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun calculate(
        containerSize: IntSizeCompat,
        contentSize: IntSizeCompat,
        contentOriginSize: IntSizeCompat,
        contentScale: ContentScaleCompat,
        minScale: Float,
        initialScale: Float,
    ): ScalesCalculator.Result = ScalesCalculator.Dynamic.calculate(
        containerSize, contentSize, contentOriginSize, contentScale, minScale, initialScale,
    )
}
