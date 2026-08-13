package ceui.pixiv.actionqueue

/** 内存版 [ActionStore]，让调度逻辑能在纯 JVM 上测，不需要 Android instrumentation。 */
internal class FakeActionStore : ActionStore {

    enum class Status { PENDING, RUNNING, FAILED }

    data class Row(
        val id: Long,
        val type: String,
        val dedupeKey: String,
        val payload: String,
        val gapMs: Long,
        var status: Status,
        var attempt: Int,
        var notBefore: Long,
        var lastError: String? = null,
        val owner: String = "",
    )

    val rows: MutableList<Row> = mutableListOf()
    private var nextId = 1L

    /** 存储层故障注入：非 null 时所有读写都抛它，用来测消费循环扛不扛得住。 */
    var failWith: (() -> Throwable)? = null

    var cooldownUntilMs: Long = 0L
        private set

    /** 造一条上次进程被杀时残留的 RUNNING，用来测冷启动恢复。 */
    fun seedRunning(type: String, payload: String, owner: String = ""): Long {
        val id = nextId++
        rows += Row(id, type, "seed:$id", payload, 0L, Status.RUNNING, 0, 0L, owner = owner)
        return id
    }

    /** 造一条上次进程留下的、还没到重试时刻的 PENDING。 */
    fun seedPending(
        type: String,
        payload: String,
        key: String,
        attempt: Int = 0,
        notBefore: Long = 0L,
        owner: String = "",
    ): Long {
        val id = nextId++
        rows += Row(id, type, key, payload, 0L, Status.PENDING, attempt, notBefore, owner = owner)
        return id
    }

    fun seedCooldown(untilMs: Long) {
        cooldownUntilMs = untilMs
    }

    private fun checkFailure() {
        failWith?.let { throw it() }
    }

    override suspend fun enqueue(request: ActionRequest, nowMs: Long, owner: String): Long {
        checkFailure()
        var attempt = 0
        var notBefore = 0L
        if (request.coalesce) {
            val doomed = rows.filter {
                it.dedupeKey == request.dedupeKey && it.owner == owner && it.status == Status.PENDING
            }
            attempt = doomed.maxOfOrNull { it.attempt } ?: 0
            notBefore = doomed.maxOfOrNull { it.notBefore } ?: 0L
            rows.removeAll(doomed)
        }
        val id = nextId++
        rows += Row(
            id = id,
            type = request.type,
            dedupeKey = request.dedupeKey,
            payload = request.payload,
            gapMs = request.gapMs,
            status = Status.PENDING,
            attempt = attempt,
            notBefore = notBefore,
            owner = owner,
        )
        return id
    }

    override suspend fun nextRunnable(nowMs: Long, owner: String): StoredAction? {
        checkFailure()
        return rows.filter {
            it.status == Status.PENDING && it.owner == owner && it.notBefore <= nowMs
        }
            .minByOrNull { it.id }
            ?.let { row ->
                StoredAction(
                    action = PendingAction(
                        id = row.id,
                        type = row.type,
                        dedupeKey = row.dedupeKey,
                        payload = row.payload,
                        attempt = row.attempt,
                        owner = row.owner,
                    ),
                    gapMs = row.gapMs,
                )
            }
    }

    override suspend fun earliestNotBeforeMs(owner: String): Long? {
        checkFailure()
        return rows.filter { it.status == Status.PENDING && it.owner == owner }
            .minOfOrNull { it.notBefore }
    }

    override suspend fun hasPending(dedupeKey: String, owner: String, excludingId: Long): Boolean {
        checkFailure()
        return rows.any {
            it.dedupeKey == dedupeKey &&
                it.owner == owner &&
                it.status == Status.PENDING &&
                it.id != excludingId
        }
    }

    override suspend fun markRunning(id: Long) {
        checkFailure()
        row(id)?.status = Status.RUNNING
    }

    override suspend fun delete(id: Long) {
        checkFailure()
        rows.removeAll { it.id == id }
    }

    override suspend fun releaseToPending(id: Long) {
        checkFailure()
        row(id)?.status = Status.PENDING
    }

    override suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long, countAttempt: Boolean) {
        checkFailure()
        row(id)?.apply {
            status = Status.PENDING
            if (countAttempt) attempt += 1
            notBefore = notBeforeMs
        }
    }

    override suspend fun markFailed(id: Long, reason: String) {
        checkFailure()
        row(id)?.apply {
            status = Status.FAILED
            lastError = reason
        }
    }

    override suspend fun resurrectRunning(): Int {
        checkFailure()
        val running = rows.filter { it.status == Status.RUNNING }
        running.forEach { it.status = Status.PENDING }
        return running.size
    }

    override suspend fun pendingCount(owner: String): Int {
        checkFailure()
        return rows.count {
            it.owner == owner && (it.status == Status.PENDING || it.status == Status.RUNNING)
        }
    }

    override suspend fun failedCount(owner: String): Int {
        checkFailure()
        return rows.count { it.owner == owner && it.status == Status.FAILED }
    }

    override suspend fun retryAllFailed(nowMs: Long, owner: String): Int {
        checkFailure()
        val failed = rows.filter { it.owner == owner && it.status == Status.FAILED }
        failed.forEach {
            it.status = Status.PENDING
            it.attempt = 0
            it.notBefore = nowMs
            it.lastError = null
        }
        return failed.size
    }

    override suspend fun clearFailed(owner: String): Int {
        checkFailure()
        val n = rows.count { it.owner == owner && it.status == Status.FAILED }
        rows.removeAll { it.owner == owner && it.status == Status.FAILED }
        return n
    }

    override suspend fun pruneFailed(owner: String, keepLatest: Int): Int {
        checkFailure()
        val retainedIds = rows
            .filter { it.owner == owner && it.status == Status.FAILED }
            .sortedByDescending { it.id }
            .take(keepLatest.coerceAtLeast(0))
            .mapTo(hashSetOf()) { it.id }
        val before = rows.size
        rows.removeAll {
            it.owner == owner && it.status == Status.FAILED && it.id !in retainedIds
        }
        return before - rows.size
    }

    override suspend fun loadCooldownUntilMs(): Long {
        checkFailure()
        return cooldownUntilMs
    }

    override suspend fun saveCooldownUntilMs(untilMs: Long) {
        checkFailure()
        cooldownUntilMs = untilMs
    }

    private fun row(id: Long): Row? = rows.firstOrNull { it.id == id }
}
