package ceui.pixiv.ui.common

import ceui.pixiv.utils.NetworkStateManager.NetworkType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedThumbnailFailureNotifierTest {

    @Before
    fun setUp() {
        FeedThumbnailFailureNotifier.resetForTesting()
    }

    @Test
    fun testShouldTrigger_emptyOrZeroFailures_returnsFalse() {
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 0, failedCards = 0))
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 3, failedCards = 0))
    }

    @Test
    fun testShouldTrigger_oneOrTwoCards_triggersOnlyWhenTwoThirdsOrMore() {
        // 1 card: 1/1 = 100% >= 2/3 -> true
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 1, failedCards = 1))

        // 2 cards: 1/2 = 50% < 2/3 -> false; 2/2 = 100% >= 2/3 -> true
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 2, failedCards = 1))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 2, failedCards = 2))
    }

    @Test
    fun testShouldTrigger_threeCards_requiresTwoOrMoreFailures() {
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 3, failedCards = 1))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 3, failedCards = 2))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 3, failedCards = 3))
    }

    @Test
    fun testShouldTrigger_fourCards_requiresThreeOrMoreFailures() {
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 4, failedCards = 2))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 4, failedCards = 3))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 4, failedCards = 4))
    }

    @Test
    fun testShouldTrigger_sixCards_requiresFourOrMoreFailures() {
        assertFalse(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 6, failedCards = 3))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 6, failedCards = 4))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 6, failedCards = 5))
        assertTrue(FeedThumbnailFailureNotifier.shouldTrigger(visibleCards = 6, failedCards = 6))
    }

    @Test
    fun testNetworkChange_resetsPoppedState() {
        assertTrue(FeedThumbnailFailureNotifier.canPop())

        FeedThumbnailFailureNotifier.setPoppedForTesting(true)
        assertFalse(FeedThumbnailFailureNotifier.canPop())
        assertTrue(FeedThumbnailFailureNotifier.isPopped())

        // Initial emission does not reset (prev is null)
        FeedThumbnailFailureNotifier.notifyNetworkChangedForTesting(NetworkType.WIFI)
        assertFalse(FeedThumbnailFailureNotifier.canPop())

        // Same network type emission does not reset
        FeedThumbnailFailureNotifier.notifyNetworkChangedForTesting(NetworkType.WIFI)
        assertFalse(FeedThumbnailFailureNotifier.canPop())

        // Network change from WIFI to CELLULAR resets state
        FeedThumbnailFailureNotifier.notifyNetworkChangedForTesting(NetworkType.CELLULAR)
        assertTrue(FeedThumbnailFailureNotifier.canPop())
        assertFalse(FeedThumbnailFailureNotifier.isPopped())

        // Mark popped again
        FeedThumbnailFailureNotifier.setPoppedForTesting(true)
        assertFalse(FeedThumbnailFailureNotifier.canPop())

        // Network change from CELLULAR to NONE resets state
        FeedThumbnailFailureNotifier.notifyNetworkChangedForTesting(NetworkType.NONE)
        assertTrue(FeedThumbnailFailureNotifier.canPop())
        assertFalse(FeedThumbnailFailureNotifier.isPopped())
    }
}
