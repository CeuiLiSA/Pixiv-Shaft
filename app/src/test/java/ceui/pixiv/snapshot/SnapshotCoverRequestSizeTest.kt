package ceui.pixiv.snapshot

import ceui.pixiv.ui.common.IllustListLayout
import ceui.pixiv.ui.common.JUSTIFIED_ROW_HEIGHT_FACTOR
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面请求尺寸（[snapshotCoverRequestSize]）：请求的宽高比必须等于卡片上的展示比例，
 * 否则 centerCrop 会先按请求比例裁一遍、ImageView 再按展示比例裁一遍。
 */
class SnapshotCoverRequestSizeTest {

    private fun assertAspect(displayRatio: Float, size: Pair<Int, Int>) {
        val (w, h) = size
        assertEquals(displayRatio, h.toFloat() / w, 0.01f)
    }

    @Test
    fun columnLayoutsRequestColumnWidthTimesRatio() {
        for (layout in listOf(IllustListLayout.MASONRY, IllustListLayout.SINGLE_COLUMN)) {
            for (ratio in floatArrayOf(0.6f, 1f, 1.4f, 2f)) {
                val size = snapshotCoverRequestSize(layout, 540, ratio)
                assertEquals(540, size.first)
                assertAspect(ratio, size)
            }
        }
        // 方格由调用方传 1:1
        assertEquals(540 to 540, snapshotCoverRequestSize(IllustListLayout.GRID, 540, 1f))
    }

    @Test
    fun justifiedRequestsTargetRowHeightAndDerivedWidth() {
        val rowHeight = (540 * JUSTIFIED_ROW_HEIGHT_FACTOR).toInt()
        for (ratio in floatArrayOf(0.6f, 1f, 1.4f, 2f)) {
            val size = snapshotCoverRequestSize(IllustListLayout.JUSTIFIED, 540, ratio)
            assertEquals(rowHeight, size.second)
            assertAspect(ratio, size)
        }
    }

    @Test
    fun neverRequestsZeroSize() {
        val (w, h) = snapshotCoverRequestSize(IllustListLayout.MASONRY, 0, 0.6f)
        assertEquals(1, w)
        assertEquals(1, h)
    }
}
