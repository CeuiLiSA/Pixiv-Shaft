package ceui.pixiv.ui.common

/**
 * 瀑布流按列表实际宽度决定列数（平板重排，issue #1087）。
 *
 * 「每行几列」设置（2/3/4）描述的是**卡片大小**：在 [REFERENCE_WIDTH_DP] 宽的手机上排几列。
 * 列表变宽（平板全屏、折叠屏展开、横屏、双栏收起）时保持卡片的物理尺寸，按宽度多排几列；
 * 窄于参考宽度时不少于设置值，手机竖屏的列数与以前一致。
 *
 * 宽度必须是列表自己的内容宽度，而不是屏幕宽：分栏、分屏、侧边导航栏都会让列表比屏幕窄。
 * 公式对应 docs/tablet-design/README.md「窗口与分栏」：
 * `columns = floor((contentWidth + gap) / (minCardWidth + gap))`。
 */
object AdaptiveStaggerColumns {

    /** 「每行几列」设置对应的参考宽度：360dp 上 2/3/4 列 → 最小卡宽 180/120/90dp。 */
    private const val REFERENCE_WIDTH_DP = 360f

    /** 与 SpacesItemDecoration 的 8dp 卡间距一致。 */
    private const val GAP_DP = 8f

    @JvmStatic
    fun columnsFor(contentWidthDp: Float, baseColumns: Int): Int {
        val base = baseColumns.coerceAtLeast(1)
        val minCardDp = REFERENCE_WIDTH_DP / base
        val fit = ((contentWidthDp + GAP_DP) / (minCardDp + GAP_DP)).toInt()
        return maxOf(base, fit)
    }
}
