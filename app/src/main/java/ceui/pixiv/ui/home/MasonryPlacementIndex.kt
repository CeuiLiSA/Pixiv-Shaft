package ceui.pixiv.ui.home

/**
 * 首页推荐瀑布流的纯数据排布索引。构造时只处理高度数字，不创建或测量 RecyclerView child。
 *
 * 坐标从列表内容顶部起算。榜单头是第 0 个 full-span 条目，头后保留两份间距；
 * 无榜单头时首排卡片从一份间距开始。列分配固定为正向最短列优先。
 */
internal class MasonryPlacementIndex private constructor(
    val columnCount: Int,
    val top: LongArray,
    val bottom: LongArray,
    val column: IntArray,
    private val prefixMaxBottom: LongArray,
    val contentBottom: Long,
) {
    val size: Int get() = top.size

    /** 找到可能与 [viewportTop] 相交的第一项；后续只需遍历到 top > viewportBottom。 */
    fun firstCandidate(viewportTop: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefixMaxBottom[middle] > viewportTop) high = middle else low = middle + 1
        }
        return low
    }

    companion object {
        fun build(
            heights: IntArray,
            fullSpan: BooleanArray,
            columnCount: Int,
            spacePx: Int,
        ): MasonryPlacementIndex {
            require(heights.size == fullSpan.size)
            return build(
                heights.size,
                fullSpan,
                columnCount,
                spacePx
            ) { position, _ -> heights[position] }
        }

        /** 高度可依赖选中的列：余数像素与左右边距会使各列卡片宽度略有不同。 */
        fun build(
            count: Int,
            fullSpan: BooleanArray,
            columnCount: Int,
            spacePx: Int,
            heightAt: (position: Int, column: Int) -> Int,
        ): MasonryPlacementIndex {
            require(count == fullSpan.size)
            require(columnCount > 0)
            require(spacePx >= 0)
            val top = LongArray(count)
            val bottom = LongArray(count)
            val column = IntArray(count)
            val prefixMaxBottom = LongArray(count)
            val ends = LongArray(columnCount) { spacePx.toLong() }
            var furthestBottom = 0L
            for (position in 0 until count) {
                if (fullSpan[position]) {
                    val start = if (position == 0) 0L else ends.max()
                    val end = start + heightAt(position, -1).coerceAtLeast(0)
                    top[position] = start
                    bottom[position] = end
                    column[position] = -1
                    ends.fill(end + if (position == 0) 2L * spacePx else spacePx.toLong())
                } else {
                    var target = 0
                    for (candidate in 1 until columnCount) {
                        if (ends[candidate] < ends[target]) target = candidate
                    }
                    val start = ends[target]
                    val end = start + heightAt(position, target).coerceAtLeast(0)
                    top[position] = start
                    bottom[position] = end
                    column[position] = target
                    ends[target] = end + spacePx
                }
                furthestBottom = maxOf(furthestBottom, bottom[position])
                prefixMaxBottom[position] = furthestBottom
            }
            return MasonryPlacementIndex(
                columnCount,
                top,
                bottom,
                column,
                prefixMaxBottom,
                furthestBottom
            )
        }
    }
}