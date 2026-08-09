package ceui.pixiv.actionqueue

/**
 * 入队请求。调用方只描述「要做什么」，不关心什么时候做。
 *
 * @param type       动作类型，用来在 [ActionHandler] 注册表里找执行器。
 * @param dedupeKey  合并键，通常是 `"$type:$targetId"`。见 [coalesce]。
 * @param payload    不透明字符串，队列不解释其内容；由注册该 [type] 的 handler 自行解析。
 * @param coalesce   true（默认）时，入队会先删掉同 [dedupeKey] 的所有 PENDING 行再插入，
 *                   即「同一目标只保留用户的最后一次意图」。连点爱心产生的
 *                   收藏→取消→收藏，最终只会发出一个请求。
 *                   已经在执行中（RUNNING）的行删不掉，此时靠 handler 幂等收敛。
 * @param gapMs      本条执行完之后，到下一条开始执行之间的额外最小间隔。
 *                   实际间隔取 `max(QueuePolicy.minGapMs, gapMs)`。默认 0 = 用全局值。
 */
public data class ActionRequest(
    public val type: String,
    public val dedupeKey: String,
    public val payload: String,
    public val coalesce: Boolean = true,
    public val gapMs: Long = 0L,
)

/** 交给 [ActionHandler] 执行的一条动作。 */
public data class PendingAction(
    public val id: Long,
    public val type: String,
    public val dedupeKey: String,
    public val payload: String,
    /** 已经失败重试过几次。首次执行为 0。 */
    public val attempt: Int,
)

/** [ActionHandler.execute] 的结果。把 HTTP 状态码翻译成这三种之一是调用方的责任。 */
public sealed interface ActionOutcome {

    /** 成功。该行立即从队列删除。 */
    public data object Success : ActionOutcome

    /**
     * 可重试的失败（429 / 5xx / 网络断开）。
     *
     * 触发的是**整队冷却**而不是单条退避：pixiv 的 429 是账号级速率限制，
     * 只让失败那条退避、后面的照常发，只会继续撞墙。
     *
     * @param retryAfterMs 响应里的 `Retry-After`（毫秒）。非空时作为冷却时长的下限，
     *                     与指数退避取较大者。
     */
    public data class Retry(
        public val retryAfterMs: Long? = null,
        public val cause: Throwable? = null,
    ) : ActionOutcome

    /**
     * 不可重试的失败（400 / 404 / 作品已删除 / 参数非法）。
     * 该行标记为 FAILED 留在库里，不再重试，也不触发冷却。
     */
    public data class Fail(
        public val reason: String,
        public val cause: Throwable? = null,
    ) : ActionOutcome
}

/** 队列对外广播的执行结果，供 UI 回滚乐观更新 / 提示用户。 */
public sealed interface ActionEvent {

    public val action: PendingAction

    public data class Succeeded(override val action: PendingAction) : ActionEvent

    /** 撞到可重试失败，已排入冷却，[retryAtMs] 之后再试。 */
    public data class Retrying(
        override val action: PendingAction,
        public val retryAtMs: Long,
        public val cause: Throwable?,
    ) : ActionEvent

    /**
     * 终态失败：要么 handler 返回了 [ActionOutcome.Fail]，要么重试次数耗尽。
     * **这是 UI 回滚乐观更新的唯一时机**（[ActionEvent.Retrying] 不要回滚，还会再试）。
     */
    public data class Failed(
        override val action: PendingAction,
        public val reason: String,
        public val cause: Throwable?,
    ) : ActionEvent
}

/** 队列当前在干什么，给状态栏 / 调试页用。 */
public sealed interface QueueState {

    /** 没有待执行的动作。 */
    public data object Idle : QueueState

    /** 有 [pending] 条待执行，正常消费中。 */
    public data class Working(public val pending: Int) : QueueState

    /** 撞了限流或网络错误，[untilMs] 之前不会再发请求。 */
    public data class CoolingDown(public val untilMs: Long, public val pending: Int) : QueueState

    /** 被 [ActionQueue.pause] 或 gate 挡住（例如未登录）。 */
    public data class Suspended(public val pending: Int) : QueueState
}
