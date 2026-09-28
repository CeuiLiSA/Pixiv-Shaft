package ceui.lisa.helper

import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.util.isEmpty
import com.github.panpf.zoomimage.view.zoom.ZoomableEngine
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import kotlin.math.max

/**
 * 当前是否处在打开时的初始缩放（用户没有手动放大）。
 *
 * 「放大大图后禁用拖动退出」靠它判断：只有回到初始缩放，竖向拖拽才会被
 * [ceui.lisa.view.DragDismissLayout] 接管去收掉页面；放大状态下的上下拖留给画面平移，
 * 不会误触退出。真实大图页与精简大图页共用这一份判定。
 *
 * 不能直接比 minScale：长图在 ReadMode 下一打开就铺满宽度，scale 本来就高于 minScale（整图适应），
 * 那样开关一开，长图就再也拖不出去。
 *
 * 容差沿用 FragmentImageDetail 里比较最大缩放时的同一口径（0.01），
 * 吸收双击 / 惯性动画停在临界点时的浮点误差。
 */
fun ZoomImageView.isAtInitialScale(): Boolean {
    val zoomable = zoomable
    val current = zoomable.transformState.value.scaleX
    return current <= zoomable.initialScale() + INITIAL_SCALE_EPSILON
}

/**
 * 按库内 calculateReadModeTransform 的口径还原初始 scale：ReadMode 接受当前尺寸时为 fillScale，
 * 否则就是 minScale。两页都不旋转内容，所以不用处理 rotation。
 */
private fun ZoomableEngine.initialScale(): Float {
    val min = minScaleState.value
    val readMode = readModeState.value ?: return min
    val container = containerSizeState.value
    val content = contentSizeState.value
    if (container.isEmpty() || content.isEmpty() ||
        contentScaleState.value == ContentScaleCompat.FillBounds ||
        !readMode.accept(contentSize = content, containerSize = container)
    ) {
        return min
    }
    val fillScale =
        max(container.width / content.width.toFloat(), container.height / content.height.toFloat())
    return max(min, fillScale)
}

private const val INITIAL_SCALE_EPSILON = 0.01f
