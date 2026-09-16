package ceui.pixiv.communication.internal

import ceui.pixiv.communication.CommunicationDiagnostic
import ceui.pixiv.communication.EventPublisher
import ceui.pixiv.communication.EventSource
import ceui.pixiv.communication.EventTopic
import ceui.pixiv.communication.EventTopicConfig
import ceui.pixiv.communication.OverflowPolicy
import ceui.pixiv.communication.PublishResult
import ceui.pixiv.communication.SubscriptionCapacityException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * Bounded per-subscriber mailboxes with all-or-nothing multicast admission. Channels only carry
 * wakeups; truth is under [lock]. All wakeups occur OUTSIDE the lock: Unconfined collectors may
 * resume synchronously and must never run application code inside a publisher's critical section.
 */
internal class DefaultEventTopic<E : Any>(
    private val config: EventTopicConfig,
    private val diagnostics: TopicDiagnostics,
) : EventTopic<E>, ManagedTopic {
    private class Subscriber<E> {
        val queue = ArrayDeque<E>()
        val wakeup = Channel<Unit>(Channel.CONFLATED)
    }

    private val lock = Any()
    private val subscribers = linkedSetOf<Subscriber<E>>()
    private val capacityChanged = MutableStateFlow(0L)
    private var closed = false

    override val source: EventSource<E> = object : EventSource<E> {
        override val events: Flow<E> = flow {
            val subscriber = synchronized(lock) {
                if (closed) return@flow
                if (subscribers.size == config.maxSubscribers) {
                    throw SubscriptionCapacityException(config.debugName)
                }
                Subscriber<E>().also { subscribers.add(it) }
            }
            try {
                reportSubscribers()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = synchronized(lock) {
                        if (closed) return@flow
                        subscriber.queue.removeFirstOrNull()
                    }
                    if (next != null) {
                        capacityChanged.update { it + 1 }
                        emit(next)
                    } else {
                        // Signals conflate safely. A publication between the empty check and
                        // receive leaves a pending wakeup, so there is no lost-subscription gap.
                        subscriber.wakeup.receiveCatching().getOrNull() ?: return@flow
                    }
                }
            } finally {
                synchronized(lock) {
                    subscribers.remove(subscriber)
                    subscriber.queue.clear()
                }
                subscriber.wakeup.close()
                capacityChanged.update { it + 1 }
                reportSubscribers()
            }
        }
    }

    override val publisher: EventPublisher<E> = object : EventPublisher<E> {
        override fun tryPublish(event: E): PublishResult = admit(event).also(::reportPublication)

        override suspend fun publish(event: E): PublishResult {
            while (true) {
                currentCoroutineContext().ensureActive()
                // Read BEFORE admission: a dequeue/close during admission cannot be missed.
                val revision = capacityChanged.value
                val result = admit(event)
                if (result != PublishResult.RejectedFull || config.overflow == OverflowPolicy.RejectNew) {
                    reportPublication(result)
                    return result
                }
                capacityChanged.first { it != revision }
            }
        }
    }

    private fun admit(event: E): PublishResult {
        val recipients = synchronized(lock) {
            if (closed) return PublishResult.Closed
            if (subscribers.any { it.queue.size >= config.bufferCapacity }) {
                return PublishResult.RejectedFull
            }
            subscribers.toList().also { targets -> targets.forEach { it.queue.addLast(event) } }
        }
        recipients.forEach { it.wakeup.trySend(Unit) }
        return PublishResult.Accepted
    }

    override fun seal(): () -> Unit {
        val recipients = synchronized(lock) {
            if (closed) return {}
            closed = true
            subscribers.toList().also {
                subscribers.forEach { subscriber -> subscriber.queue.clear() }
                subscribers.clear()
            }
        }
        return {
            recipients.forEach { it.wakeup.close() }
            capacityChanged.update { it + 1 }
            diagnostics.report(CommunicationDiagnostic.Closed(config.debugName))
        }
    }

    private fun reportPublication(result: PublishResult) {
        diagnostics.report(CommunicationDiagnostic.Publication(config.debugName, result))
    }

    private fun reportSubscribers() {
        val count = synchronized(lock) { subscribers.size }
        diagnostics.report(CommunicationDiagnostic.SubscribersChanged(config.debugName, count))
    }
}
