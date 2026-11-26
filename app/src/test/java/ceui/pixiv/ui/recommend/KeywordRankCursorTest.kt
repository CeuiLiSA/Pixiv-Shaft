package ceui.pixiv.ui.recommend

import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.LoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordRankCursorTest {
    @Test
    fun failedRefreshLeavesTheCommittedCursorScopeIntact() {
        val previous = KeywordRankCursor.decode(null, initialRestriction = 0)
        val next = previous.next("opaque-cursor")

        assertEquals(KeywordRankCursor(2, null), KeywordRankCursor.decode(null, initialRestriction = 2))
        assertEquals(KeywordRankCursor(0, "opaque-cursor"), KeywordRankCursor.decode(next, initialRestriction = 2))
    }

    @Test
    fun detailOffsetsKeepTheFirstPagesRestrictionAndVersion() {
        val first = KeywordRankCursor.decode(null, initialRestriction = 2)
        val next = KeywordRankCursor.decode(first.next("30", "page-version"), initialRestriction = 0)

        assertEquals(2, next.restriction)
        assertEquals(30, next.position?.toInt())
        assertEquals("page-version", next.version)
        assertNull(next.next(null))
        assertEquals(KeywordRankCursor(2, "30"), KeywordRankCursor.decode(first.next("30"), initialRestriction = 0))
    }

    @Test
    fun aBoundedEmptyRangeCanBeContinuedWhileATrueEndCannot() {
        val stopped = FeedUiState(reachedEnd = true)

        assertTrue(stopped.canContinueKeywordRank("more"))
        assertFalse(stopped.canContinueKeywordRank(null))
        assertFalse(stopped.copy(append = LoadState.Loading).canContinueKeywordRank("more"))
    }
}
