package ceui.pixiv.ui.referral

import org.junit.Assert.*
import org.junit.Test

class ReferralDemoRepositoryTest {
    private val now = 1_800_000_000_000L
    private val description = "介绍 Pixiv-Shaft 的收藏功能与实际使用体验，并附上官方下载入口。"

    @Test fun rewardsRequireApprovalAndCannotBeClaimedTwice() {
        val repo = ReferralDemoRepository()
        assertFalse(repo.claim(ReferralTask.RECOMMEND, now))
        assertTrue(repo.submit(ReferralTask.RECOMMEND, "https://example.com/post", description, true))
        assertFalse(repo.claim(ReferralTask.RECOMMEND, now))
        assertFalse(repo.submit(ReferralTask.RECOMMEND, "https://example.com/post2", description, true))
        assertTrue(repo.review(ReferralTask.RECOMMEND, true))
        assertTrue(repo.claim(ReferralTask.RECOMMEND, now))
        assertFalse(repo.claim(ReferralTask.RECOMMEND, now))
        assertEquals(7, repo.snapshot.earnedDays)
        assertEquals(1, repo.snapshot.cards.size)
    }

    @Test fun rejectedContentCanBeCorrectedAndResubmitted() {
        val repo = ReferralDemoRepository()
        assertTrue(repo.submit(ReferralTask.TUTORIAL, "https://example.com/tutorial", description, true))
        assertTrue(repo.review(ReferralTask.TUTORIAL, false))
        assertEquals(ReferralStatus.REJECTED, repo.snapshot.status(ReferralTask.TUTORIAL))
        assertFalse(repo.review(ReferralTask.TUTORIAL, true))
        assertTrue(repo.submit(ReferralTask.TUTORIAL, " https://example.com/fixed ", description, true))
        assertEquals("https://example.com/fixed", repo.snapshot.submissions.getValue(ReferralTask.TUTORIAL).url)
        assertTrue(repo.review(ReferralTask.TUTORIAL, true))
        assertTrue(repo.claim(ReferralTask.TUTORIAL, now))
        assertEquals(30, repo.snapshot.earnedDays)
    }

    @Test fun invalidSubmissionDoesNotChangeTask() {
        val repo = ReferralDemoRepository()
        listOf("javascript:alert(1)", "file:///etc/example", "https://user:pass@example.com/a", "not a URL").forEach {
            assertFalse(repo.submit(ReferralTask.RECOMMEND, it, description, true))
        }
        assertFalse(repo.submit(ReferralTask.RECOMMEND, "https://example.com/a", "too short", true))
        assertFalse(repo.submit(ReferralTask.RECOMMEND, "https://example.com/a", description, false))
        assertFalse(repo.submit(ReferralTask.INVITE, "https://example.com/a", description, true))
        assertEquals(ReferralSnapshot(), repo.snapshot)
    }

    @Test fun activationStacksAndIsIdempotent() {
        val repo = ReferralDemoRepository(ReferralSnapshot(activeUntil = now + 10 * ReferralDemoRepository.DAY))
        assertTrue(repo.claim(ReferralTask.INVITE, now))
        assertEquals(now + 10 * ReferralDemoRepository.DAY, repo.snapshot.activeUntil)
        assertTrue(repo.activate(ReferralTask.INVITE, now + 1))
        assertEquals(now + 17 * ReferralDemoRepository.DAY, repo.snapshot.activeUntil)
        assertFalse(repo.activate(ReferralTask.INVITE, now + 2))
        assertEquals(0, repo.snapshot.unused(now + 2))
    }

    @Test fun expiryBoundaryPreventsActivation() {
        val expired = ReferralDemoRepository()
        expired.claim(ReferralTask.INVITE, now)
        val deadline = now + 30 * ReferralDemoRepository.DAY
        assertEquals(1, expired.snapshot.unused(deadline - 1))
        assertEquals(0, expired.snapshot.unused(deadline))
        assertFalse(expired.activate(ReferralTask.INVITE, deadline))
        assertEquals(0L, expired.snapshot.activeUntil)
        val valid = ReferralDemoRepository()
        valid.claim(ReferralTask.INVITE, now)
        assertTrue(valid.activate(ReferralTask.INVITE, deadline - 1))
        assertEquals(deadline - 1 + 7 * ReferralDemoRepository.DAY, valid.snapshot.activeUntil)
    }

    @Test fun retainedInvitesCannotExceedEffectiveInvitesAndMonthRewardIsOnce() {
        val repo = ReferralDemoRepository()
        repeat(5) { repo.addRetained() }
        assertEquals(1, repo.snapshot.retained)
        assertFalse(repo.claim(ReferralTask.CIRCLE, now))
        repeat(2) { repo.addInvite(); repo.addRetained() }
        assertTrue(repo.claim(ReferralTask.CIRCLE, now))
        repeat(5) { repo.addRetained() }
        assertEquals(3, repo.snapshot.retained)
        assertFalse(repo.claim(ReferralTask.CIRCLE, now))
        repo.reset()
        assertEquals(ReferralSnapshot(), repo.snapshot)
    }
}
