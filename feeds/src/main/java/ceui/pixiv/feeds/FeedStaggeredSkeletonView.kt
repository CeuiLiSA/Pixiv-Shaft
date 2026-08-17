package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 瀑布流首屏骨架图：等宽列 + 高度按固定比例循环。shimmer / 动画生命周期全在 [FeedSkeletonView]。
 *
 * 块高固定比例（`[0.6,2.0]`，对齐真实卡片的钳制区间）→ 确定性零 jitter；8dp 圆角/间距对齐真实
 * 瀑布流卡；列数读自 live 的 StaggeredGridLayoutManager（跟随用户「每行几列」设置）。
 */
class FeedStaggeredSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    /** 列数，与真实瀑布流一致（读自 StaggeredGridLayoutManager.spanCount）。 */
    var spanCount: Int = 2
        set(value) {
            val v = value.coerceAtLeast(1)
            if (field != v) {
                field = v
                rebuildBlocks()
                invalidate()
            }
        }

    private val gap = 8f * density
    private val corner = 8f * density

    private val heightRatios = floatArrayOf(
        1.32f, 0.78f, 1.0f, 1.55f, 0.86f, 1.18f, 0.68f, 1.44f, 1.08f, 0.92f, 1.6f, 0.74f,
    )

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val colWidth = (w - gap * (spanCount + 1)) / spanCount
        if (colWidth <= 0f) return
        var idx = 0
        for (col in 0 until spanCount) {
            val left = gap + col * (colWidth + gap)
            var top = gap
            while (top < h) {
                val blockH = colWidth * heightRatios[idx % heightRatios.size]
                idx++
                out.add(block(left, top, colWidth, blockH, corner))
                top += blockH + gap
            }
        }
    }
}
