package ceui.pixiv.ui.home

import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecmdR18InterleaveTest {

    private fun mockIllust(id: Long, isR18: Boolean = false): Illust {
        return Illust(
            id = id,
            title = "Illust $id",
            x_restrict = if (isR18) 1 else 0,
        )
    }

    @Test
    fun `interleaveIllusts with empty inputs`() {
        val base = listOf("a", "b", "c")
        val injected = listOf("x", "y")

        assertEquals(base, interleaveIllusts(base, emptyList(), step = 3))
        assertEquals(injected, interleaveIllusts(emptyList(), injected, step = 3))
        assertEquals(emptyList<String>(), interleaveIllusts(emptyList<String>(), emptyList(), step = 3))
    }

    @Test
    fun `interleaveIllusts preserves base relative order and inserts every step items`() {
        val base = listOf("A1", "A2", "A3", "A4", "A5", "A6")
        val injected = listOf("R1", "R2")

        val result = interleaveIllusts(base, injected, step = 3)
        // A1, A2, A3, R1, A4, A5, A6, R2
        assertEquals(listOf("A1", "A2", "A3", "R1", "A4", "A5", "A6", "R2"), result)
    }

    @Test
    fun `interleaveIllusts realistic 30 base plus 10 injected in 2-column grid`() {
        val base = (1..30).map { "N$it" }
        val injected = (1..10).map { "R$it" }

        val result = interleaveIllusts(base, injected, step = 3)
        assertEquals(40, result.size)

        // Injected items should appear at index 3, 7, 11, 15, 19, 23, 27, 31, 35, 39
        val expectedInjectedIndices = listOf(3, 7, 11, 15, 19, 23, 27, 31, 35, 39)
        expectedInjectedIndices.forEachIndexed { index, pos ->
            assertEquals("R${index + 1}", result[pos])
        }

        // Base items must preserve original sequence
        val nonRItems = result.filter { it.startsWith("N") }
        assertEquals(base, nonRItems)
    }

    @Test
    fun `interleaveIllusts handles fewer injected items than available slots`() {
        val base = listOf("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8")
        val injected = listOf("R1")

        val result = interleaveIllusts(base, injected, step = 3)
        assertEquals(listOf("A1", "A2", "A3", "R1", "A4", "A5", "A6", "A7", "A8"), result)
    }

    @Test
    fun `interleaveIllusts appends remaining injected if injected exceeds available slots`() {
        val base = listOf("A1", "A2", "A3")
        val injected = listOf("R1", "R2", "R3")

        val result = interleaveIllusts(base, injected, step = 3)
        assertEquals(listOf("A1", "A2", "A3", "R1", "R2", "R3"), result)
    }

    @Test
    fun `interleaveIllusts with non-positive step falls back to prepending injected`() {
        val base = listOf("A1", "A2")
        val injected = listOf("R1", "R2")

        val result = interleaveIllusts(base, injected, step = 0)
        assertEquals(listOf("R1", "R2", "A1", "A2"), result)
    }

    @Test
    fun `R18RecommendBuffer reset takeBatch and clear lifecycle`() {
        val buffer = R18RecommendBuffer()
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)

        val items = (101L..105L).map { mockIllust(it, isR18 = true) }
        buffer.reset(items)

        assertFalse(buffer.isEmpty)
        assertEquals(5, buffer.size)

        // Invalid count
        assertEquals(emptyList<Illust>(), buffer.takeBatch(0))
        assertEquals(emptyList<Illust>(), buffer.takeBatch(-1))
        assertEquals(5, buffer.size)

        // Take batch of 2
        val batch1 = buffer.takeBatch(2)
        assertEquals(listOf(101L, 102L), batch1.map { it.id })
        assertEquals(3, buffer.size)

        // Take batch of 4 (only 3 left)
        val batch2 = buffer.takeBatch(4)
        assertEquals(listOf(103L, 104L, 105L), batch2.map { it.id })
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)

        // Take from empty
        assertEquals(emptyList<Illust>(), buffer.takeBatch(2))

        // Reset replaces items
        buffer.reset(listOf(mockIllust(201L, isR18 = true)))
        assertEquals(1, buffer.size)
        buffer.clear()
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `R18RecommendBuffer filters excludedIds to prevent duplication`() {
        val buffer = R18RecommendBuffer()
        val items = listOf(101L, 102L, 103L, 104L).map { mockIllust(it, isR18 = true) }
        buffer.reset(items)

        // 102 is already in the current page
        val batch = buffer.takeBatch(count = 2, excludedIds = setOf(102L))
        assertEquals(listOf(101L, 103L), batch.map { it.id })
        assertEquals(1, buffer.size) // 104 remains
    }

    @Test
    fun `multi-page simulation verifies cross-page smooth interleaving and natural exhaustion`() {
        val buffer = R18RecommendBuffer()
        // Suppose Web returns 18 R-18 items
        val webR18 = (1001L..1018L).map { mockIllust(it, isR18 = true) }
        buffer.reset(webR18)

        // Page 1: App API returns 9 items (1..9)
        val page1App = (1L..9L).map { mockIllust(it) }
        val page1R18 = buffer.takeBatch(page1App.size / RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertEquals(3, page1R18.size)
        assertEquals(listOf(1001L, 1002L, 1003L), page1R18.map { it.id })

        val page1Merged = interleaveIllusts(page1App, page1R18, step = RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertEquals(12, page1Merged.size)
        assertEquals(listOf(1L, 2L, 3L, 1001L, 4L, 5L, 6L, 1002L, 7L, 8L, 9L, 1003L), page1Merged.map { it.id })
        assertEquals(15, buffer.size)

        // Page 2: App API returns next 9 items (10..18)
        val page2App = (10L..18L).map { mockIllust(it) }
        val page2R18 = buffer.takeBatch(page2App.size / RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertEquals(3, page2R18.size)
        assertEquals(listOf(1004L, 1005L, 1006L), page2R18.map { it.id })

        val page2Merged = interleaveIllusts(page2App, page2R18, step = RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertEquals(12, page2Merged.size)
        assertEquals(listOf(10L, 11L, 12L, 1004L, 13L, 14L, 15L, 1005L, 16L, 17L, 18L, 1006L), page2Merged.map { it.id })
        assertEquals(12, buffer.size)

        // Drain until exhausted (simulate 4 more pages)
        repeat(4) {
            val appWorks = (19L..27L).map { mockIllust(it) }
            buffer.takeBatch(appWorks.size / RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        }
        assertTrue(buffer.isEmpty)

        // Subsequent page when buffer is exhausted
        val page7App = (28L..36L).map { mockIllust(it) }
        val page7R18 = buffer.takeBatch(page7App.size / RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertTrue(page7R18.isEmpty())
        val page7Merged = interleaveIllusts(page7App, page7R18, step = RecmdIllustFeedFragment.R18_INTERLEAVE_STEP)
        assertEquals(page7App, page7Merged)
    }
}
