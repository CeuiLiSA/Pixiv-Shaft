package ceui.pixiv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasonryPlacementIndexTest {
    @Test
    fun fullSpanHeaderStartsBothColumnsAtSameLine() {
        val index = MasonryPlacementIndex.build(
            intArrayOf(200, 100, 100, 100),
            booleanArrayOf(true, false, false, false),
            columnCount = 2,
            spacePx = 8,
        )
        assertEquals(0L, index.top[0])
        assertEquals(216L, index.top[1])
        assertEquals(216L, index.top[2])
        assertEquals(0, index.column[1])
        assertEquals(1, index.column[2])
        assertEquals(324L, index.top[3])
    }

    @Test
    fun deepAnchorDoesNotChangeTheTopDownAssignment() {
        val count = 10_001
        val heights = IntArray(count) { if (it == 0) 200 else 80 + (it * 37 % 220) }
        val fullSpan = BooleanArray(count).apply { this[0] = true }
        val index = MasonryPlacementIndex.build(heights, fullSpan, columnCount = 3, spacePx = 8)

        for (position in 1 until count) {
            val col = index.column[position]
            assertTrue(col in 0..2)
            assertEquals(heights[position].toLong(), index.bottom[position] - index.top[position])
        }
        assertEquals(1, index.firstCandidate(index.top[1]))
        val deep = 9000
        assertTrue(index.firstCandidate(index.top[deep]) <= deep)
        assertEquals(count, index.firstCandidate(index.contentBottom))
    }


    @Test
    fun tallCardRemainsACandidateWhileShortColumnContinues() {
        val index = MasonryPlacementIndex.build(
            intArrayOf(200, 500, 100, 100, 100),
            booleanArrayOf(true, false, false, false, false),
            columnCount = 2,
            spacePx = 8,
        )
        assertEquals(216L, index.top[1])
        assertEquals(216L, index.top[2])
        assertEquals(1, index.firstCandidate(400))
    }
}