package ceui.pixiv.ui.referral

import ceui.pixiv.shaftapi.ReferralBoundTo
import ceui.pixiv.shaftapi.ReferralRewardDto
import ceui.pixiv.shaftapi.ReferralRulesDto
import ceui.pixiv.shaftapi.ReferralStateResponse
import ceui.pixiv.shaftapi.ReferralTaskState
import org.junit.Assert.*
import org.junit.Test

/** The live server owns reward eligibility; regress the client contract, not the removed demo. */
class ReferralModelsTest {
    @Test fun ruleCopyUsesTheSelectedCampaignsServerRules() {
        val snapshot = ReferralStateResponse(rules = ReferralRulesDto(
            qualifyWindowDays = 10, qualifyActiveDays = 4, retainWindowDays = 21,
            retainActiveDays = 5, retainLateFromDay = 11, cardValidDays = 60,
        )).toSnapshot()
        assertArrayEquals(arrayOf<Any>(10, 4, 21, 5, 11, 60), snapshot.ruleArgs)
        assertArrayEquals(arrayOf<Any>(7, 2, 14, 3, 8, 30), ReferralSnapshot().ruleArgs)
    }

    @Test fun mapsServerTiersAndPreservesDifferentCardsForTheSameTask() {
        val snapshot = ReferralStateResponse(
            campaign = "new",
            campaigns = listOf("new", "old"),
            tasks = listOf(ReferralTaskState(key = "tutorial", plan = "max", days = 30, status = "ready")),
            rewards = listOf(
                ReferralRewardDto(id = 1, task = "tutorial", plan = "pro", days = 7, expiresAt = 100),
                ReferralRewardDto(id = 2, task = "tutorial", plan = "max", days = 30, expiresAt = 200),
            ),
        ).toSnapshot()
        assertEquals("MAX", planLabel(snapshot.view(ReferralTask.TUTORIAL)?.plan))
        assertEquals(listOf(1L, 2L), snapshot.cards.map { it.id })
        assertEquals(1, snapshot.unused(100))
        assertEquals(0, snapshot.unused(200))
        assertEquals(37, snapshot.earnedDays)
        assertEquals(1, snapshot.claimable)
    }

    @Test fun closedCampaignPreservesPendingAndHistoricalParticipants() {
        assertTrue(ReferralStateResponse(enabled = false, boundTo = ReferralBoundTo(inviterUid = 12)).toSnapshot().hasSomethingToSettle)
        assertTrue(ReferralStateResponse(enabled = false, tasks = listOf(ReferralTaskState(key = "tutorial", status = "pending"))).toSnapshot().hasSomethingToSettle)
        assertTrue(ReferralStateResponse(enabled = false, campaigns = listOf("new", "old")).toSnapshot().hasSomethingToSettle)
        assertFalse(ReferralSnapshot().hasSomethingToSettle)
    }

    @Test fun invitationParserAcceptsLinksButNeverGuessesAmbiguousCodes() {
        assertEquals("K7M2QX4P", parseReferralCode("https://pixshaft.com/i/k7m2qx4p"))
        assertEquals("K7M2QX4P", parseReferralCode(" K7M2-QX4P "))
        assertNull(parseReferralCode("0000OOOO"))
        assertNull(parseReferralCode("x".repeat(201)))
    }

    @Test fun failedBookmarkCanRetryAndEachAccountHasItsOwnDailyGate() {
        val gate = ReferralBookmarkGate()
        assertTrue(gate.begin(1, 10))
        assertFalse(gate.begin(1, 10))
        assertTrue(gate.begin(2, 10))
        gate.complete(1, 10, false)
        assertTrue(gate.begin(1, 10))
        gate.complete(1, 10, true)
        assertFalse(gate.begin(1, 10))
        gate.complete(2, 10, true)
        assertTrue(gate.begin(1, 11))
    }
}
