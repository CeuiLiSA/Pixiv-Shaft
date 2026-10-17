package ceui.pixiv.ui.home

import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.IllustFeedItem
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

    private class MockFeedItem(override val feedKey: Any) : ceui.pixiv.feeds.FeedItem

    @Test
    fun `interleaveFeedItems preserves header positions and only interleaves on IllustFeedItem`() {
        val header = MockFeedItem("header_rank")
        val n1 = IllustFeedItem(mockIllust(1L))
        val n2 = IllustFeedItem(mockIllust(2L))
        val n3 = IllustFeedItem(mockIllust(3L))
        val n4 = IllustFeedItem(mockIllust(4L))

        val r1 = IllustFeedItem(mockIllust(101L, isR18 = true))
        val r2 = IllustFeedItem(mockIllust(102L, isR18 = true))

        val baseItems = listOf(header, n1, n2, n3, n4)
        val injectedItems = listOf(r1, r2)

        val result = interleaveFeedItems(baseItems, injectedItems, step = 3)
        // Step 3 applies to IllustFeedItem:
        // header (non-illust, count=0)
        // n1 (illust 1)
        // n2 (illust 2)
        // n3 (illust 3 -> triggers r1 insertion)
        // r1
        // n4 (illust 4)
        // remaining injected: r2 appended at end
        assertEquals(listOf(header, n1, n2, n3, r1, n4, r2), result)
    }

    @Test
    fun `interleaveFeedItems with empty injected returns base items unmodified`() {
        val n1 = IllustFeedItem(mockIllust(1L))
        val n2 = IllustFeedItem(mockIllust(2L))
        val baseItems = listOf(n1, n2)

        val result = interleaveFeedItems(baseItems, emptyList(), step = 3)
        assertEquals(baseItems, result)
    }

    @Test
    fun `interleaveFeedItems with empty base returns injected items`() {
        val r1 = IllustFeedItem(mockIllust(101L, isR18 = true))
        val result = interleaveFeedItems(emptyList(), listOf(r1), step = 3)
        assertEquals(listOf(r1), result)
    }

    @Test
    fun `interleaveFeedItems realistic first-page compensation`() {
        val header = MockFeedItem("header_rank")
        val normalIllusts = (1L..12L).map { IllustFeedItem(mockIllust(it)) }
        val r18Illusts = (101L..104L).map { IllustFeedItem(mockIllust(it, isR18 = true)) }

        val base = listOf(header) + normalIllusts
        val result = interleaveFeedItems(base, r18Illusts, step = 3)

        assertEquals(1 + 12 + 4, result.size)
        // Header must stay at index 0
        assertEquals(header, result[0])

        // R-18 items inserted after illust 3, 6, 9, 12
        // Indices in result:
        // 0: header
        // 1: 1L, 2: 2L, 3: 3L
        // 4: 101L (R18)
        // 5: 4L, 6: 5L, 7: 6L
        // 8: 102L (R18)
        // 9: 7L, 10: 8L, 11: 9L
        // 12: 103L (R18)
        // 13: 10L, 14: 11L, 15: 12L
        // 16: 104L (R18)
        assertEquals(101L, (result[4] as IllustFeedItem).illust.id)
        assertEquals(102L, (result[8] as IllustFeedItem).illust.id)
        assertEquals(103L, (result[12] as IllustFeedItem).illust.id)
        assertEquals(104L, (result[16] as IllustFeedItem).illust.id)

        // Non-R18 items preserve relative order
        val resultNormalIds = result.filterIsInstance<IllustFeedItem>()
            .filter { it.illust.x_restrict == 0 }
            .map { it.illust.id }
        assertEquals((1L..12L).toList(), resultNormalIds)
    }
}

