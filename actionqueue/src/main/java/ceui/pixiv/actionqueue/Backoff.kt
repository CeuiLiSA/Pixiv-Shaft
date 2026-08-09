package ceui.pixiv.actionqueue

import kotlin.math.min
import kotlin.random.Random

/**
 * 指数退避 + 抖动，算出撞到可重试失败之后整队要冷却多久。
 *
 * @param priorAttempts 这条动作**之前**已经失败过几次（首次失败传 0）。
 * @param retryAfterMs  服务端 `Retry-After`。服务端说的话优先级高于指数退避：即使超过
 *                      [QueuePolicy.maxCooldownMs] 也照听，硬顶着它继续发只会被继续拒。
 *                      但采信有上限 [QueuePolicy.maxRetryAfterMs] —— 一个离谱的大值
 *                      （或者被中间层改写过的头）能把整队冻到进程结束，而冻住期间用户
 *                      每一次收藏都只是变红然后石沉大海。
 */
internal fun computeCooldownMs(
    priorAttempts: Int,
    policy: QueuePolicy,
    retryAfterMs: Long?,
    random: Random,
): Long {
    // shl 超过 62 会溢出成负数；实际 attempt 受 maxAttempts 限制，这里只是防御。
    val shift = min(priorAttempts, 20)
    val exponential = min(policy.initialCooldownMs shl shift, policy.maxCooldownMs)

    // 抖动：多个任务/多台设备在同一时刻结束冷却会再次一起撞墙。
    val jitterSpan = exponential * policy.jitterRatio
    val jittered = if (jitterSpan <= 0.0) {
        exponential
    } else {
        (exponential + random.nextDouble(-jitterSpan, jitterSpan)).toLong()
    }

    val serverAsked = (retryAfterMs ?: 0L).coerceIn(0L, policy.maxRetryAfterMs)
    return maxOf(jittered.coerceAtLeast(0L), serverAsked)
}
