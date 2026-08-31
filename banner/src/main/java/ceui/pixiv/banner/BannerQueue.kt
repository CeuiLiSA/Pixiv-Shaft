package ceui.pixiv.banner

/**
 * Priority-aware FIFO queue plus the policy resolver for banner requests.
 * Single-threaded by design — all entry points must be called from the
 * same (main) thread; the invariant is enforced at runtime by
 * [assertOwnerThread].
 *
 * Ordering: priority DESC, arrival ASC within a priority.
 */
internal class BannerQueue(
    private val maxSize: Int,
) {

    init {
        require(maxSize > 0) { "BannerQueue maxSize must be > 0, was $maxSize" }
    }

    private val pending: ArrayDeque<BannerRequest> = ArrayDeque()
    private var current: BannerRequest? = null

    @Volatile
    private var ownerThread: Thread? = null

    private fun assertOwnerThread() {
        val thread = Thread.currentThread()
        val owner = ownerThread
        if (owner == null) {
            ownerThread = thread
            return
        }
        check(owner === thread) {
            "BannerQueue accessed from thread '${thread.name}' but is owned by " +
                "'${owner.name}'. BannerQueue is single-threaded by design."
        }
    }

    sealed class SubmitOutcome {
        data object Accepted : SubmitOutcome()
        data object AcceptedPreempt : SubmitOutcome()
        data class Dropped(val cause: DropCause) : SubmitOutcome()
        data class Replaced(val displacedId: String) : SubmitOutcome()
        data class ReplacedCurrent(val displacedId: String) : SubmitOutcome()
        data class AcceptedWithOverflow(val evictedId: String) : SubmitOutcome()
    }

    fun submit(request: BannerRequest): SubmitOutcome {
        assertOwnerThread()
        return when (request.policy) {
            BannerDisplayPolicy.Enqueue -> handleEnqueue(request)
            BannerDisplayPolicy.Replace -> handleReplace(request)
            BannerDisplayPolicy.DropIfShowing -> handleDropIfShowing(request)
            BannerDisplayPolicy.Preempt -> handlePreempt(request)
        }
    }

    private fun handleEnqueue(request: BannerRequest): SubmitOutcome {
        if (pending.size >= maxSize) {
            val tail = pending.last()
            if (tail.priority.ordinal >= request.priority.ordinal) {
                return SubmitOutcome.Dropped(DropCause.QUEUE_FULL)
            }
            val evicted = pending.removeLast()
            insertSorted(request)
            return SubmitOutcome.AcceptedWithOverflow(evictedId = evicted.id)
        }
        insertSorted(request)
        return SubmitOutcome.Accepted
    }

    private fun handleReplace(request: BannerRequest): SubmitOutcome {
        if (request.dedupKey == null) return handleEnqueue(request)
        if (current?.dedupKey == request.dedupKey) {
            val displacedId = current!!.id
            return SubmitOutcome.ReplacedCurrent(displacedId)
        }
        val matchIndex = pending.indexOfFirst { it.dedupKey == request.dedupKey }
        if (matchIndex >= 0) {
            val displaced = pending.removeAt(matchIndex)
            insertSorted(request)
            return SubmitOutcome.Replaced(displacedId = displaced.id)
        }
        return handleEnqueue(request)
    }

    private fun handleDropIfShowing(request: BannerRequest): SubmitOutcome {
        if (current?.category == request.category) {
            return SubmitOutcome.Dropped(DropCause.POLICY_REJECTED)
        }
        return handleEnqueue(request)
    }

    private fun handlePreempt(request: BannerRequest): SubmitOutcome {
        if (current != null) return SubmitOutcome.AcceptedPreempt
        return handleEnqueue(request)
    }

    private fun insertSorted(request: BannerRequest) {
        var insertAt = pending.size
        for (i in pending.indices) {
            if (pending[i].priority.ordinal < request.priority.ordinal) {
                insertAt = i
                break
            }
        }
        pending.add(insertAt, request)
    }

    fun pushFront(request: BannerRequest) {
        assertOwnerThread()
        pending.addFirst(request)
    }

    fun markPresenting(request: BannerRequest) {
        assertOwnerThread()
        current = request
    }

    fun onPresentationFinished(id: String) {
        assertOwnerThread()
        if (current?.id == id) current = null
    }

    fun pollNext(): BannerRequest? {
        assertOwnerThread()
        return pending.removeFirstOrNull()
    }

    fun remove(id: String): BannerRequest? {
        assertOwnerThread()
        val idx = pending.indexOfFirst { it.id == id }
        if (idx < 0) return null
        return pending.removeAt(idx)
    }

    fun removeByCategory(category: BannerCategory): List<BannerRequest> {
        assertOwnerThread()
        val removed = mutableListOf<BannerRequest>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (r.category == category) {
                removed += r
                it.remove()
            }
        }
        return removed
    }

    fun clear(): List<BannerRequest> {
        assertOwnerThread()
        val all = pending.toList()
        pending.clear()
        return all
    }

    fun size(): Int {
        assertOwnerThread()
        return pending.size
    }

    fun currentRequest(): BannerRequest? {
        assertOwnerThread()
        return current
    }
}
