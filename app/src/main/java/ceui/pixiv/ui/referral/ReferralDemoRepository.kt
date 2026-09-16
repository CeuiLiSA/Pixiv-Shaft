package ceui.pixiv.ui.referral

import java.net.URI

/** Local preview domain only. This never reads or updates account entitlements. */
internal enum class ReferralTask(val days: Int, val target: Int = 1) {
    INVITE(7), RECOMMEND(7), TUTORIAL(30), CIRCLE(30, 3),
}

internal enum class ReferralStatus { NEW, PROGRESS, PENDING, REJECTED, READY, CLAIMED }

internal data class ReferralCard(
    val task: ReferralTask,
    val claimedAt: Long,
    val expiresAt: Long,
    val activatedAt: Long = 0,
)

internal data class ReferralSubmission(val url: String, val description: String)

internal data class ReferralSnapshot(
    val statuses: Map<ReferralTask, ReferralStatus> = mapOf(
        ReferralTask.INVITE to ReferralStatus.READY,
        ReferralTask.RECOMMEND to ReferralStatus.NEW,
        ReferralTask.TUTORIAL to ReferralStatus.NEW,
        ReferralTask.CIRCLE to ReferralStatus.PROGRESS,
    ),
    val effective: Int = 1,
    val retained: Int = 0,
    val cards: List<ReferralCard> = emptyList(),
    val submissions: Map<ReferralTask, ReferralSubmission> = emptyMap(),
    val activeUntil: Long = 0,
) {
    fun status(task: ReferralTask): ReferralStatus = statuses.getValue(task)
    fun progress(task: ReferralTask): Int = when (task) {
        ReferralTask.INVITE -> effective.coerceAtMost(1)
        ReferralTask.CIRCLE -> retained.coerceAtMost(3)
        else -> if (status(task) in setOf(ReferralStatus.READY, ReferralStatus.CLAIMED)) 1 else 0
    }
    val claimable: Int get() = statuses.values.count { it == ReferralStatus.READY }
    val earnedDays: Int get() = cards.sumOf { it.task.days }
    fun unused(now: Long): Int = cards.count { it.activatedAt == 0L && it.expiresAt > now }
}

internal class ReferralDemoRepository(initial: ReferralSnapshot = ReferralSnapshot()) {
    var snapshot: ReferralSnapshot = initial
        private set

    fun submit(task: ReferralTask, url: String, description: String, confirmed: Boolean): Boolean {
        if (task !in listOf(ReferralTask.RECOMMEND, ReferralTask.TUTORIAL) ||
            snapshot.status(task) !in listOf(ReferralStatus.NEW, ReferralStatus.REJECTED) ||
            !confirmed || description.trim().length !in 20..1000 || url.length > 2000
        ) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme !in listOf("http", "https") || uri.host?.contains('.') != true || uri.userInfo != null) return false
        snapshot = snapshot.copy(
            statuses = snapshot.statuses + (task to ReferralStatus.PENDING),
            submissions = snapshot.submissions + (task to ReferralSubmission(url.trim(), description.trim())),
        )
        return true
    }

    fun review(task: ReferralTask, approved: Boolean): Boolean {
        if (snapshot.status(task) != ReferralStatus.PENDING) return false
        snapshot = snapshot.copy(statuses = snapshot.statuses +
            (task to if (approved) ReferralStatus.READY else ReferralStatus.REJECTED))
        return true
    }

    fun claim(task: ReferralTask, now: Long): Boolean {
        if (snapshot.status(task) != ReferralStatus.READY || snapshot.cards.any { it.task == task }) return false
        snapshot = snapshot.copy(
            statuses = snapshot.statuses + (task to ReferralStatus.CLAIMED),
            cards = snapshot.cards + ReferralCard(task, now, now + 30 * DAY),
        )
        return true
    }

    fun activate(task: ReferralTask, now: Long): Boolean {
        val card = snapshot.cards.find { it.task == task } ?: return false
        if (card.activatedAt != 0L || card.expiresAt <= now) return false
        snapshot = snapshot.copy(
            activeUntil = maxOf(now, snapshot.activeUntil) + task.days * DAY,
            cards = snapshot.cards.map { if (it.task == task) it.copy(activatedAt = now) else it },
        )
        return true
    }

    fun addInvite() {
        snapshot = snapshot.copy(effective = (snapshot.effective + 1).coerceAtMost(99))
    }

    fun addRetained() {
        val count = (snapshot.retained + 1).coerceAtMost(minOf(snapshot.effective, 3))
        snapshot = snapshot.copy(retained = count,
            statuses = if (count == 3 && snapshot.status(ReferralTask.CIRCLE) != ReferralStatus.CLAIMED)
                snapshot.statuses + (ReferralTask.CIRCLE to ReferralStatus.READY) else snapshot.statuses)
    }

    fun reset() { snapshot = ReferralSnapshot() }

    companion object { const val DAY = 86_400_000L }
}
