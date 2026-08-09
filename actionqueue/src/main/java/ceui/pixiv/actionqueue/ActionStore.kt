package ceui.pixiv.actionqueue

/** 队列取出来准备执行的一条，比 [PendingAction] 多带引擎自己要用的调度字段。 */
public data class StoredAction(
    public val action: PendingAction,
    /** 本条执行完之后的额外节流间隔，见 [ActionRequest.gapMs]。 */
    public val gapMs: Long,
)

/**
 * 队列的持久化后端。
 *
 * 抽成接口有两个实际好处：引擎的调度逻辑可以在纯 JVM 单测里用内存假实现跑完，
 * 不需要 Android instrumentation；以及将来想换掉 Room 时不用碰调度代码。
 * 生产实现是 [ActionQueue.withRoomStore] 里装配的 Room 版。
 *
 * 所有方法都在 IO 上下文被调用，实现不需要自己切线程。
 */
public interface ActionStore {

    /**
     * 入队，返回行 id。
     *
     * [ActionRequest.coalesce] 为 true 时，实现**必须**在同一个事务里先删掉同
     * [ActionRequest.dedupeKey] 的所有 PENDING 行再插入 —— 分两步做会在高频连点下
     * 漏掉并发插进来的行。
     */
    public suspend fun enqueue(request: ActionRequest, nowMs: Long): Long

    /** 取下一条可执行的（PENDING 且 notBefore <= [nowMs]），按入队顺序。没有则返回 null。 */
    public suspend fun nextRunnable(nowMs: Long): StoredAction?

    /**
     * 所有 PENDING 行里最早的 notBefore。用来算「该睡多久」，
     * 避免退避中的行被兜底轮询间隔拖慢。全空则返回 null。
     */
    public suspend fun earliestNotBeforeMs(): Long?

    /** 标记为执行中。进程若在此后被杀，靠 [resurrectRunning] 复位。 */
    public suspend fun markRunning(id: Long)

    /** 执行成功，删除该行。 */
    public suspend fun delete(id: Long)

    /** 放回 PENDING 且**不**增加尝试次数。用于队列被主动停止时归还在手的行。 */
    public suspend fun releaseToPending(id: Long)

    /** 尝试次数 +1，放回 PENDING，并把 notBefore 推到 [notBeforeMs]。保持原有的排队顺序。 */
    public suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long)

    /** 终态失败，留在库里供用户查看和手动重试。 */
    public suspend fun markFailed(id: Long, reason: String)

    /**
     * 冷启动恢复：把上次进程被杀时残留的 RUNNING 复位成 PENDING。
     * @return 复位了几条。
     */
    public suspend fun resurrectRunning(): Int

    public suspend fun pendingCount(): Int

    public suspend fun failedCount(): Int

    /** 把所有 FAILED 重置为 PENDING（尝试次数清零）。@return 重置了几条。 */
    public suspend fun retryAllFailed(nowMs: Long): Int

    /** 清空 FAILED。@return 删了几条。 */
    public suspend fun clearFailed(): Int
}
