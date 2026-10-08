package ceui.pixiv.ui.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 齐行布局分行（[packJustifiedRows]，#1214）。 */
class JustifiedRowsTest {

    private fun pack(
        aspects: FloatArray,
        width: Int = 1000,
        inset: Int = 20,
        targetHeight: Float = 700f,
        fullSpan: BooleanArray = BooleanArray(aspects.size),
    ) = packJustifiedRows(aspects, fullSpan, width, inset, targetHeight)

    /** 按「整行正好 = 宽度」把 spans 切成行，返回每行的下标区间。 */
    private fun rowsOf(spans: IntArray, width: Int = 1000): List<IntRange> {
        val rows = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        spans.forEachIndexed { i, span ->
            used += span
            if (used >= width) {
                rows += start..i
                start = i + 1
                used = 0
            }
        }
        if (start < spans.size) rows += start until spans.size
        return rows
    }

    @Test
    fun `收好的行正好铺满宽度且同行等高`() {
        val aspects = floatArrayOf(0.7f, 1.3f, 0.75f, 0.6f, 1.6f, 0.8f, 0.7f, 1.0f, 0.66f, 1.2f)
        val spans = pack(aspects)
        val rows = rowsOf(spans)
        // 最后一行可能没收满，前面的都必须正好 1000
        rows.dropLast(1).forEach { row ->
            assertEquals(1000, row.sumOf { spans[it] })
            val heights = row.map { (spans[it] - 20) / aspects[it] }
            assertTrue("同行高度差过大: $heights", heights.max() - heights.min() <= 2.5f)
        }
    }

    @Test
    fun `超宽时取行高更接近目标的收法而不是一律收进去`() {
        // 两张竖图 1.4 × 700 + 40 = 1020 已超宽：收进第二张行高 ≈ 686（偏差 1.02），
        // 只放一张行高 ≈ 1400（偏差 2.0）→ 应该两张一行
        val spans = pack(floatArrayOf(0.7f, 0.7f, 0.7f, 0.7f))
        assertArrayEquals(intArrayOf(500, 500, 500, 500), spans)
    }

    @Test
    fun `停在超出那张之前时行被拉高而不是压矮`() {
        // 0.6, 0.6 → 加第三张 0.6：1.8 × 700 + 60 > 1000，收进去行高 ≈ 522（偏差 1.34），
        // 停在它之前行高 = 800（偏差 1.14）→ 不收，两张一行、行高 800
        val spans = pack(floatArrayOf(0.6f, 0.6f, 0.6f, 0.6f))
        val rows = rowsOf(spans)
        assertEquals(listOf(0..1, 2..3), rows)
        val height = (spans[0] - 20) / 0.6f
        assertTrue("行高 $height 应高于目标 700", height > 700f)
    }

    @Test
    fun `整行条目自成一行并打断前面没收满的行`() {
        val aspects = floatArrayOf(1f, 0.7f, 0.7f, 0.7f)
        val fullSpan = booleanArrayOf(true, false, false, false)
        val spans = pack(aspects, fullSpan = fullSpan)
        assertEquals(1000, spans[0])
        assertEquals(1000, spans[1] + spans[2])

        // 末尾 footer 前没凑满的一行：保持目标行高，宽度不超
        val tail = pack(floatArrayOf(0.7f, 1f), fullSpan = booleanArrayOf(false, true))
        assertEquals(510, tail[0]) // 0.7 × 700 + 20
        assertEquals(1000, tail[1])
    }

    @Test
    fun `极宽的单张自成一行并按宽度缩放`() {
        val spans = pack(floatArrayOf(5f, 0.7f, 0.7f))
        assertEquals(1000, spans[0])
        assertTrue(abs((spans[0] - 20) / 5f - 196f) < 1f)
    }

    @Test
    fun `空列表`() {
        assertEquals(0, pack(FloatArray(0)).size)
    }
}
