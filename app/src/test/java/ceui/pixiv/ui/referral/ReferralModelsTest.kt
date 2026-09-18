package ceui.pixiv.ui.referral

import android.app.Application
import android.content.res.Configuration
import ceui.lisa.R
import ceui.pixiv.shaftapi.ReferralBoundTo
import ceui.pixiv.shaftapi.ReferralRewardDto
import ceui.pixiv.shaftapi.ReferralRulesDto
import ceui.pixiv.shaftapi.ReferralStateResponse
import ceui.pixiv.shaftapi.ReferralTaskState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** The live server owns reward eligibility; regress the client contract, not the removed demo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReferralModelsTest {
    @Test fun missingRewardDurationsDefaultToSevenDaysForEveryTaskAndWelcome() {
        val snapshot = ReferralStateResponse(
            tasks = ReferralTask.LISTED.reversed().map { ReferralTaskState(key = it.key) },
            rewards = ReferralTask.entries.mapIndexed { index, task ->
                ReferralRewardDto(id = index + 1L, task = task.key)
            },
        ).toSnapshot()
        assertEquals(listOf("invite", "recommend", "circle", "tutorial"), snapshot.tasks.map { it.task.key })
        assertEquals(listOf(7, 7, 7, 7), snapshot.tasks.map { it.days })
        assertEquals(listOf(7, 7, 7, 7, 7), snapshot.cards.map { it.days })
    }

    @Test fun allLocalesDescribeSevenDayRewardsAndTwentyEightDayTaskTotal() {
        val app = RuntimeEnvironment.getApplication()
        for (tag in listOf("zh-CN", "zh-TW", "en", "ja", "ko", "ru", "tr")) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val resources = app.createConfigurationContext(config).resources
            for (task in ReferralTask.LISTED) {
                val description = resources.getString(task.copy().description)
                assertTrue("$tag ${task.key}: $description", description.contains("7"))
                assertFalse("$tag ${task.key}: $description", description.contains("30"))
                if (task == ReferralTask.CIRCLE || task == ReferralTask.TUTORIAL) {
                    assertTrue("$tag ${task.key}: $description", description.contains("MAX"))
                }
            }
            val circleSteps = resources.getString(R.string.referral_circle_steps, 14, 3, 8)
            assertTrue("$tag: $circleSteps", circleSteps.contains("7"))
            assertFalse("$tag: $circleSteps", circleSteps.contains("30"))
            // Use a distinct activation deadline so a stale 30-day reward cannot hide behind it.
            val rules = resources.getString(R.string.referral_rules_body, 7, 2, 14, 3, 8, 45)
            assertTrue("$tag: $rules", rules.contains("28"))
            assertTrue("$tag: $rules", rules.contains("45"))
            assertFalse("$tag: $rules", rules.contains("30") || rules.contains("74"))
        }
    }

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
            tasks = listOf(
                ReferralTaskState(key = "tutorial", plan = "max", days = 7, status = "ready"),
                ReferralTaskState(key = "circle", plan = "max"),
            ),
            rewards = listOf(
                ReferralRewardDto(id = 1, task = "tutorial", plan = "pro", days = 7, expiresAt = 100),
                ReferralRewardDto(id = 2, task = "tutorial", plan = "max", days = 30, expiresAt = 200),
            ),
        ).toSnapshot()
        assertEquals("MAX", planLabel(snapshot.view(ReferralTask.TUTORIAL)?.plan))
        assertEquals(listOf(ReferralTask.CIRCLE, ReferralTask.TUTORIAL), snapshot.tasks.map { it.task })
        assertEquals(listOf(7, 7), snapshot.tasks.map { it.days })
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
