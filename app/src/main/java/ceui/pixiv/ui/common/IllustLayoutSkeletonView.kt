package ceui.pixiv.ui.common

import android.content.Context
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock

/**
 * 「方格 / 齐行」两种插画列表布局的首屏骨架（#1214）。瀑布流与单列沿用 feeds 的
 * [ceui.pixiv.feeds.FeedStaggeredSkeletonView]（单列就是一列的瀑布流），不在这里。
 *
 * 几何逐项照搬真实列表，骨架换成真卡时不跳位：
 * - 方格：SGLM + SpacesItemDecoration —— 外缘与中缝都是 [spacePx]，块恒 1:1，列数取本次装配的 SGLM；
 * - 齐行：[JustifiedLayoutManager] —— 列表 padding 半格 + 每张卡左右各半格，分行、目标行高、
 *   列数都走同一套 [packJustifiedRows] / [JUSTIFIED_ROW_HEIGHT_FACTOR] / [justifiedColumnsFor]。
 *
 * 示例比例与瀑布流骨架同一组（高 / 宽 0.68~1.6），多种布局的骨架看起来是同一批「作品」。
 */
internal class IllustLayoutSkeletonView(
    context: Context,
    private val layout: IllustListLayout,
    /** 方格：本次 SGLM 的列数；齐行：「每行几列」设置（平板按宽度另行加列）。 */
    private val columns: Int,
    /** 与列表 decoration 同一个值（8dp 换算的整数像素）。 */
    private val spacePx: Int,
) : FeedSkeletonView(context) {

    private val corner = 8f * density

    private val heightRatios = floatArrayOf(
        1.32f, 0.78f, 1.0f, 1.55f, 0.86f, 1.18f, 0.68f, 1.44f, 1.08f, 0.92f, 1.6f, 0.74f,
    )

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        when (layout) {
            IllustListLayout.GRID -> buildGrid(w, h, out)
            IllustListLayout.JUSTIFIED -> buildJustified(w, h, out)
            // 不会走到：这两种由 FeedStaggeredSkeletonView 画
            IllustListLayout.MASONRY, IllustListLayout.SINGLE_COLUMN -> Unit
        }
    }

    private fun buildGrid(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val gap = spacePx.toFloat()
        val n = columns.coerceAtLeast(1)
        val side = (w - gap * (n + 1)) / n
        if (side <= 0f) return
        var top = gap
        while (top < h) {
            for (col in 0 until n) {
                out.add(block(gap + col * (side + gap), top, side, side, corner))
            }
            top += side + gap
        }
    }

    private fun buildJustified(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val half = spacePx / 2
        val inset = 2 * half
        // 真实列表：左右 padding 各半格，内容宽 = 列表宽 - 两侧 padding
        val width = (w - 2 * half).toInt()
        if (width <= inset) return
        val n = justifiedColumnsFor(resources, width, columns)
        val columnWidth = (width - n * inset).toFloat() / n
        // 一屏最多也就十几张，多给一些保证铺满再截断
        val count = 48
        val aspects = FloatArray(count) { 1f / heightRatios[it % heightRatios.size] }
        val spans = packJustifiedRows(
            aspects, BooleanArray(count), width, inset, columnWidth * JUSTIFIED_ROW_HEIGHT_FACTOR,
        )
        var top = spacePx.toFloat()
        var rowStart = 0
        var used = 0
        for (i in 0 until count) {
            if (top >= h) break
            used += spans[i]
            // 满一行（正好铺满内容宽）才落块：行高取行首那张，同行各张误差 ≤ 1–2px
            if (used < width && i < count - 1) continue
            val rowHeight = (spans[rowStart] - inset) / aspects[rowStart]
            var left = half.toFloat()
            for (k in rowStart..i) {
                out.add(block(left + half, top, (spans[k] - inset).toFloat(), rowHeight, corner))
                left += spans[k]
            }
            top += rowHeight + spacePx
            rowStart = i + 1
            used = 0
        }
    }
}
