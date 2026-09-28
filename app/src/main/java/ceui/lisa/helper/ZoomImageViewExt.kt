package ceui.lisa.helper

import com.github.panpf.zoomimage.ZoomImageView

/**
 * 当前是否处在最小缩放（适应视图）。
 *
 * 「放大大图后禁用拖动退出」靠它判断：只有回到最小缩放，竖向拖拽才会被
 * [ceui.lisa.view.DragDismissLayout] 接管去收掉页面；放大状态下的上下拖留给画面平移，
 * 不会误触退出。真实大图页与精简大图页共用这一份判定。
 *
 * 容差沿用 FragmentImageDetail 里比较最大缩放时的同一口径（0.01），
 * 吸收双击 / 惯性动画停在临界点时的浮点误差。
 */
fun ZoomImageView.isAtMinScale(): Boolean {
    val zoomable = zoomable
    val current = zoomable.transformState.value.scaleX
    val min = zoomable.minScaleState.value
    return current <= min + MIN_SCALE_EPSILON
}

private const val MIN_SCALE_EPSILON = 0.01f
