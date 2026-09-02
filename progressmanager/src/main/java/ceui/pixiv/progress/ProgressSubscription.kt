package ceui.pixiv.progress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ProgressTracker.track] 的回执。[close] 解除注册，幂等。
 *
 * 之所以返回一个句柄而不是让调用方再喊一次 `untrack(url, listener)`：
 * 解除注册总是发生在 `finally` 里，调用方在那个位置手上往往只剩一个句柄；
 * 而且 `AutoCloseable` 让 `use {}` 直接可用，注册和解除就锁死在同一个作用域里。
 */
public class ProgressSubscription internal constructor(
    private val tracker: ProgressTracker,
    private val key: String,
    private val listener: ProgressListener,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    public val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tracker.untrack(key, listener)
    }
}
