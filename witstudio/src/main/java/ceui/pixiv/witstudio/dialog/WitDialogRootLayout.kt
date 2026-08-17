package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.widget.FrameLayout
import ceui.pixiv.witstudio.theme.WitDisplay
import kotlin.math.min

/**
 * 弹窗的外层容器，替代 `QMUIDialogRootLayout`：负责把卡片钳在合理的宽高里。
 *
 * 宽度取 `min(屏宽 - 2×32dp, 340dp)` 并且是 **EXACTLY** —— 固定宽的弹窗在同一个 app 里
 * 视觉更稳（QMUI 是 260~320dp 之间 wrap，导致「取消/确定」和「取消/我知道了」两个弹窗宽度不一样）。
 *
 * 高度是 AT_MOST，上限取「窗口给的可用高」和「屏高 × 85%」里更小的那个。
 * 前者不能丢：软键盘弹起时 `ADJUST_RESIZE` 就是通过收窄这个 spec 来通知我们的，
 * 直接用屏高会让输入框弹窗被键盘盖住。
 */
public class WitDialogRootLayout internal constructor(
    context: Context,
    public val dialogView: WitDialogView,
    layoutParams: LayoutParams,
) : FrameLayout(context) {

    private val insetHorizontalPx =
        WitDisplay.dp2px(context, WitDialogMetrics.INSET_HORIZONTAL_DP)
    private val maxWidthPx = WitDisplay.dp2px(context, WitDialogMetrics.MAX_WIDTH_DP)

    init {
        addView(dialogView, layoutParams)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = resources.displayMetrics
        val width = min(
            min(MeasureSpec.getSize(widthMeasureSpec), metrics.widthPixels - 2 * insetHorizontalPx),
            maxWidthPx,
        ).coerceAtLeast(1)
        val maxHeight = min(
            MeasureSpec.getSize(heightMeasureSpec),
            (metrics.heightPixels * WitDialogMetrics.MAX_HEIGHT_PERCENT).toInt(),
        ).coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }
}
