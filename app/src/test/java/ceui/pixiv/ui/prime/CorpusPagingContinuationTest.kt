package ceui.pixiv.ui.prime

import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.LoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusPagingContinuationTest {
    @Test
    fun aBoundedEmptyRangeCanBeContinuedWhileATrueEndCannot() {
        val stopped = FeedUiState(reachedEnd = true)

        assertTrue(stopped.canContinueCorpusPaging("more"))
        assertFalse(stopped.canContinueCorpusPaging(null))
        assertFalse(stopped.copy(append = LoadState.Loading).canContinueCorpusPaging("more"))
    }
}
