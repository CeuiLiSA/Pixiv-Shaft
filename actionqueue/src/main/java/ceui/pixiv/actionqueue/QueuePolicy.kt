package ceui.pixiv.actionqueue

/**
 * 队列的节流与退避参数。
 *
 * @param minGapMs         两次**执行**之间的最小间隔。注意语义：不是「入队后等这么久再执行」，
 *                         而是「上一条执行完到下一条开始执行至少隔这么久」—— 429 限制的是
 *                         单位时间请求数，节流点必须在消费侧的执行间隙。
 *                         冷启动后的第一条不受此限制，立即执行。
 * @param maxAttempts      单条动作的最大执行次数（含首次）。耗尽后标 FAILED 不再重试。
 * @param initialCooldownMs 首次撞到可重试失败后的整队冷却时长。
 * @param maxCooldownMs    指数退避算出来的冷却时长上限。
 * @param maxRetryAfterMs  服务端 `Retry-After` 的采信上限。服务端说的话优先级高于指数退避
 *                         （见 [computeCooldownMs]），但不能无上限：一个 `Retry-After: 86400`
 *                         就能把整队冻住一整天，期间用户每一次收藏都只是乐观变红然后石沉大海，
 *                         界面上没有任何地方能看出队列已经停了。超过这个值按这个值算，
 *                         到点重试一次；真还在限流里，下一轮 429 会重新把冷却顶起来。
 * @param jitterRatio      冷却时长的随机抖动比例，避免多设备/多任务同时恢复后又一起撞墙。
 *                         0.2 表示实际时长在 [0.8x, 1.2x] 之间。
 * @param idlePollMs       队列空闲时的兜底轮询间隔。正常情况下靠入队信号唤醒，
 *                         这个值只是防止信号丢失导致永久睡死。
 */
public data class QueuePolicy(
    public val minGapMs: Long = 2_000L,
    public val maxAttempts: Int = 5,
    public val initialCooldownMs: Long = 30_000L,
    public val maxCooldownMs: Long = 5 * 60_000L,
    public val maxRetryAfterMs: Long = 30 * 60_000L,
    public val jitterRatio: Double = 0.2,
    public val idlePollMs: Long = 60_000L,
) {
    init {
        require(minGapMs >= 0) { "minGapMs must be >= 0" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialCooldownMs > 0) { "initialCooldownMs must be > 0" }
        require(maxCooldownMs >= initialCooldownMs) { "maxCooldownMs must be >= initialCooldownMs" }
        require(maxRetryAfterMs >= maxCooldownMs) { "maxRetryAfterMs must be >= maxCooldownMs" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0, 1]" }
        require(idlePollMs > 0) { "idlePollMs must be > 0" }
    }

    /**
     * 单次冷却在这套参数下可能达到的最长时长，即 [computeCooldownMs] 返回值的上确界：
     * 指数退避封顶在 [maxCooldownMs] 再叠一层正向抖动，服务端 `Retry-After` 封顶在
     * [maxRetryAfterMs]，两者取大。
     *
     * 用来钳制从库里读回的冷却截止时刻（见 [ActionQueue.start]）—— 任何一个超过
     * 「此刻 + 本值」的截止时刻，都不可能是这个队列在一个正常的墙钟下写出来的。
     */
    internal val maxPossibleCooldownMs: Long
        get() = maxOf(maxRetryAfterMs, (maxCooldownMs * (1.0 + jitterRatio)).toLong())
}
