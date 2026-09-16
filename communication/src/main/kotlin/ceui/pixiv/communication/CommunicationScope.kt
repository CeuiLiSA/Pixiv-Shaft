package ceui.pixiv.communication

import ceui.pixiv.communication.internal.DefaultEventTopic
import ceui.pixiv.communication.internal.DefaultStateTopic
import ceui.pixiv.communication.internal.ManagedTopic
import ceui.pixiv.communication.internal.TopicDiagnostics

/**
 * Owns typed topic instances. No global registry, thread, dispatcher, background job, or Android
 * dependency. Close explicitly when the owning session/flow ends. Constructors perform no I/O.
 * A process owner may retain its instance for the process lifetime.
 */
public class CommunicationScope @JvmOverloads public constructor(
    observer: CommunicationObserver = CommunicationObserver {},
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val maxTopics: Int = 128,
) : AutoCloseable {
    private val lock = Any()
    private val topics = mutableListOf<ManagedTopic>()
    private var closed = false
    private val diagnostics = TopicDiagnostics(observer)

    init { require(maxTopics > 0) { "maxTopics must be positive" } }

    public fun <E : Any> eventTopic(config: EventTopicConfig): EventTopic<E> = synchronized(lock) {
        checkCanCreate()
        DefaultEventTopic<E>(config, diagnostics).also { topics.add(it) }
    }

    public fun <K : Any, S : Any> stateTopic(config: StateTopicConfig): StateTopic<K, S> =
        synchronized(lock) {
            checkCanCreate()
            DefaultStateTopic<K, S>(config, clock, diagnostics).also { topics.add(it) }
        }

    private fun checkCanCreate() {
        if (closed) throw ScopeClosedException()
        check(topics.size < maxTopics) { "Topic capacity exceeded ($maxTopics)" }
    }

    /**
     * Discard queued data and wake suspended publishers/collectors. Already dequeued values may
     * finish handling; arbitrary downstream handlers are not interrupted. Cancel their collection
     * Job when prompt interruption is needed (the Android adapter does this on lifecycle exit).
     * After this method returns, every owned topic rejects new writes and registrations.
     */
    override fun close() {
        val notifications = synchronized(lock) {
            if (closed) return
            val actions = topics.map { it.seal() }
            closed = true
            topics.clear()
            actions
        }
        notifications.forEach { it() }
    }
}
