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
    )

    val rows: MutableList<Row> = mutableListOf()
    private var nextId = 1L

    /** 造一条上次进程被杀时残留的 RUNNING，用来测冷启动恢复。 */
    fun seedRunning(type: String, payload: String): Long {
        val id = nextId++
        rows += Row(id, type, "seed:$id", payload, 0L, Status.RUNNING, 0, 0L)
        return id
    }

    override suspend fun enqueue(request: ActionRequest, nowMs: Long): Long {
        if (request.coalesce) {
            rows.removeAll { it.dedupeKey == request.dedupeKey && it.status == Status.PENDING }
        }
        val id = nextId++
        rows += Row(
            id = id,
            type = request.type,
            dedupeKey = request.dedupeKey,
            payload = request.payload,
            gapMs = request.gapMs,
            status = Status.PENDING,
            attempt = 0,
            notBefore = 0L,
        )
        return id
    }

    override suspend fun nextRunnable(nowMs: Long): StoredAction? =
        rows.filter { it.status == Status.PENDING && it.notBefore <= nowMs }
            .minByOrNull { it.id }
            ?.let { row ->
                StoredAction(
                    action = PendingAction(
                        id = row.id,
                        type = row.type,
                        dedupeKey = row.dedupeKey,
                        payload = row.payload,
                        attempt = row.attempt,
                    ),
                    gapMs = row.gapMs,
                )
            }

    override suspend fun earliestNotBeforeMs(): Long? =
        rows.filter { it.status == Status.PENDING }.minOfOrNull { it.notBefore }

    override suspend fun markRunning(id: Long) {
        row(id)?.status = Status.RUNNING
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun releaseToPending(id: Long) {
        row(id)?.status = Status.PENDING
    }

    override suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long) {
        row(id)?.apply {
            status = Status.PENDING
            attempt += 1
            notBefore = notBeforeMs
        }
    }

    override suspend fun markFailed(id: Long, reason: String) {
        row(id)?.apply {
            status = Status.FAILED
            lastError = reason
        }
    }

    override suspend fun resurrectRunning(): Int {
        val running = rows.filter { it.status == Status.RUNNING }
        running.forEach { it.status = Status.PENDING }
        return running.size
    }

    override suspend fun pendingCount(): Int =
        rows.count { it.status == Status.PENDING || it.status == Status.RUNNING }

    override suspend fun failedCount(): Int = rows.count { it.status == Status.FAILED }

    override suspend fun retryAllFailed(nowMs: Long): Int {
        val failed = rows.filter { it.status == Status.FAILED }
        failed.forEach {
            it.status = Status.PENDING
            it.attempt = 0
            it.notBefore = nowMs
            it.lastError = null
        }
        return failed.size
    }

    override suspend fun clearFailed(): Int {
        val n = rows.count { it.status == Status.FAILED }
        rows.removeAll { it.status == Status.FAILED }
        return n
    }

    private fun row(id: Long): Row? = rows.firstOrNull { it.id == id }
}
