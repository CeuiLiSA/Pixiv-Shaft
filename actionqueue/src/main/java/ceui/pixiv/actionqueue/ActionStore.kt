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
 * ## 线程
 *
 * 调用方**不保证** dispatcher。消费循环与入队泵跑在构造 [ActionQueue] 时传进来的 scope 上
 * （生产装配是 `Dispatchers.IO`），但 [ActionQueue] 那几个公开的 suspend 方法
 * （`forget` / `clearFailed` / `retryAllFailed` / `pendingCount` / `failedCount` /
 * `enqueueAndWait`）是在**调用方**的上下文里跑的 —— app 侧就是在 `Dispatchers.Default` 上调的。
 * 所以实现要做阻塞 IO 的话必须自己切线程；生产实现是 Room，它的 suspend DAO 自带调度，
 * 因此不需要额外处理。
 *
 * ## owner
 *
 * 每一行都带一个入队时刻的归属标识（app 侧传登录用户 id）。取行、计数、合并**一律**
 * 按 owner 过滤：库是跨登录态持久的，不分归属的话 A 排队没发完的收藏会在 B 登录之后
 * 用 B 的 token 发出去。
 */
public interface ActionStore {

    /**
     * 入队，返回行 id。
     *
     * [ActionRequest.coalesce] 为 true 时，实现**必须**在同一个事务里先删掉同 [owner] 下
     * 同 [ActionRequest.dedupeKey] 的所有 PENDING 行再插入 —— 分两步做会在高频连点下
     * 漏掉并发插进来的行。
     *
     * 被合并掉的行里如果有正在退避重试的（attempt > 0 / notBefore 在未来），新行**必须**
     * 继承它们中最大的 attempt 和 notBefore：否则用户对着一个一直失败的目标反复点，
     * 每点一次重试预算就清零一次，这条动作永远到不了终态失败，既不回滚也不提示，
     * 还会一直把整队冷却顶起来。
     */
    public suspend fun enqueue(request: ActionRequest, nowMs: Long, owner: String): Long

    /**
     * 取下一条可执行的（属于 [owner]、PENDING 且 notBefore <= [nowMs]），按入队顺序。
     * 没有则返回 null。
     */
    public suspend fun nextRunnable(nowMs: Long, owner: String): StoredAction?

    /**
     * [owner] 名下所有 PENDING 行里最早的 notBefore。用来算「该睡多久」，
     * 避免退避中的行被兜底轮询间隔拖慢。全空则返回 null。
     */
    public suspend fun earliestNotBeforeMs(owner: String): Long?

    /**
     * [owner] 名下是否还有同 [dedupeKey] 的 PENDING 行（[excludingId] 那条不算）。
     * 用来判断一条失败的动作是不是已经被用户更新的意图顶掉了，见
     * [ActionEvent.Failed.supersededByPending]。
     */
    public suspend fun hasPending(dedupeKey: String, owner: String, excludingId: Long): Boolean

    /** 标记为执行中。进程若在此后被杀，靠 [resurrectRunning] 复位。 */
    public suspend fun markRunning(id: Long)

    /** 执行成功（或调用方主动丢弃），删除该行。 */
    public suspend fun delete(id: Long)

    /** 放回 PENDING 且**不**增加尝试次数。用于队列被主动停止时归还在手的行。 */
    public suspend fun releaseToPending(id: Long)

    /**
     * 放回 PENDING 并把 notBefore 推到 [notBeforeMs]。保持原有的排队顺序（id 不变）。
     *
     * @param countAttempt 尝试次数要不要 +1。断网这类「请求根本没发出去」的失败传 false，
     *                     见 [ActionOutcome.Retry.countsAsAttempt]。
     */
    public suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long, countAttempt: Boolean)

    /** 终态失败，留在库里供调用方回滚后清理。 */
    public suspend fun markFailed(id: Long, reason: String)

    /**
     * 冷启动恢复：把上次进程被杀时残留的 RUNNING 复位成 PENDING。
     * @return 复位了几条。
     */
    public suspend fun resurrectRunning(): Int

    public suspend fun pendingCount(owner: String): Int

    public suspend fun failedCount(owner: String): Int

    /** 把 [owner] 名下所有 FAILED 重置为 PENDING（尝试次数清零）。@return 重置了几条。 */
    public suspend fun retryAllFailed(nowMs: Long, owner: String): Int

    /** 清空 [owner] 名下的 FAILED。@return 删了几条。 */
    public suspend fun clearFailed(owner: String): Int

    /**
     * 读回上次持久化的整队冷却截止时刻，没有则 0。
     *
     * 冷却必须过进程：撞 429 之后队列要静默 30 秒到 5 分钟，而「后台待了几分钟被系统回收」
     * 恰恰是这个窗口里最可能发生的事。只存在内存里的话，下次启动会带着一整队 notBefore=0
     * 的行原地重新撞同一个还没过期的账号级限流。
     */
    public suspend fun loadCooldownUntilMs(): Long

    public suspend fun saveCooldownUntilMs(untilMs: Long)
}
