package ceui.pixiv.communication.internal

import ceui.pixiv.communication.CommunicationDiagnostic
import ceui.pixiv.communication.MonotonicClock
import ceui.pixiv.communication.StateEntry
import ceui.pixiv.communication.StateRetention
import ceui.pixiv.communication.StateSource
import ceui.pixiv.communication.StateTopic
import ceui.pixiv.communication.StateTopicConfig
import ceui.pixiv.communication.StateWriteResult
import ceui.pixiv.communication.StateWriter
import ceui.pixiv.communication.SubscriptionCapacityException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class DefaultStateTopic<K : Any, S : Any>(
    private val config: StateTopicConfig,
    private val clock: MonotonicClock,
    private val diagnostics: TopicDiagnostics,
) : StateTopic<K, S>, ManagedTopic {
    private class Slot<S : Any>(var lastAccess: Long) {
        var value: StateEntry<S> = StateEntry.Unknown
        val listeners = linkedSetOf<Channel<Unit>>()
    }

    private val lock = Any()
    private val slots = linkedMapOf<K, Slot<S>>()
    private var subscriberCount = 0
    private var closed = false
    private var reducing = false

    override val source: StateSource<K, S> = StateSource { key -> observe(key) }

    override val writer: StateWriter<K, S> = object : StateWriter<K, S> {
        override fun set(key: K, value: S): StateWriteResult = write(key) { value }

        override fun update(key: K, transform: (StateEntry<S>) -> S): StateWriteResult =
            write(key, transform)

        override fun remove(key: K): StateWriteResult {
            val listeners = synchronized(lock) {
                checkNotReducing()
                if (closed) return StateWriteResult.CLOSED
                val slot = slots[key] ?: return StateWriteResult.UNCHANGED
                if (slot.listeners.isEmpty()) {
                    slots.remove(key)
                    return if (slot.value == StateEntry.Unknown) StateWriteResult.UNCHANGED
                    else StateWriteResult.APPLIED
                }
                if (slot.value == StateEntry.Unknown) return StateWriteResult.UNCHANGED
                slot.value = StateEntry.Unknown
                slot.listeners.toList()
            }
            listeners.forEach { it.trySend(Unit) }
            return StateWriteResult.APPLIED
        }
    }

    private fun write(key: K, transform: (StateEntry<S>) -> S): StateWriteResult {
        val listeners = synchronized(lock) {
            checkNotReducing()
            if (closed) return StateWriteResult.CLOSED
            val now = clock.nowMillis()
            val existing = currentSlot(key, now)
            // Check room first, but do not evict until the transform succeeds.
            val victim = if (existing == null && slots.size >= config.maxKeys) evictionCandidate() else null
            if (existing == null && slots.size >= config.maxKeys && victim == null) {
                return StateWriteResult.REJECTED_CAPACITY
            }
            reducing = true
            val next = try {
                StateEntry.Value(transform(existing?.value ?: StateEntry.Unknown))
            } finally {
                reducing = false
            }
            if (existing?.value == next) {
                existing.lastAccess = now
                return StateWriteResult.UNCHANGED
            }
            if (victim != null) slots.remove(victim)
            val slot = existing ?: Slot<S>(now).also { slots[key] = it }
            slot.value = next
            slot.lastAccess = now
            slot.listeners.toList()
        }
        listeners.forEach { it.trySend(Unit) }
        return StateWriteResult.APPLIED
    }

    private fun observe(key: K): Flow<StateEntry<S>> = flow {
        val listener = Channel<Unit>(Channel.CONFLATED)
        val slot = synchronized(lock) {
            checkNotReducing()
            if (closed) return@flow
            if (subscriberCount >= config.maxSubscribers) {
                throw SubscriptionCapacityException(config.debugName)
            }
            val now = clock.nowMillis()
            val existing = currentSlot(key, now)
            if (existing == null && slots.size >= config.maxKeys) {
                val victim = evictionCandidate() ?: throw SubscriptionCapacityException(config.debugName)
                slots.remove(victim)
            }
            (existing ?: Slot<S>(now).also { slots[key] = it }).also {
                it.listeners.add(listener)
                it.lastAccess = now
                subscriberCount++
            }
        }
        try {
            reportSubscribers()
            var last: StateEntry<S>? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = synchronized(lock) {
                    if (closed) return@flow
                    slot.value
                }
                if (last != next) {
                    last = next
                    emit(next)
                }
                listener.receiveCatching().getOrNull() ?: return@flow
            }
        } finally {
            synchronized(lock) {
                if (slot.listeners.remove(listener)) subscriberCount--
                if (!closed && slot.listeners.isEmpty()) {
                    slot.lastAccess = clock.nowMillis()
                    if (slot.value == StateEntry.Unknown ||
                        (config.retention as? StateRetention.Cache)?.idleTtlMillis == 0L
                    ) {
                        slots.remove(key)
                    }
                }
            }
            listener.close()
            reportSubscribers()
        }
    }

    /** Called with lock held. Expiration is lazy; key count is bounded even without traffic. */
    private fun currentSlot(key: K, now: Long): Slot<S>? {
        val slot = slots[key] ?: return null
        val cache = config.retention as? StateRetention.Cache
        if (cache != null && slot.listeners.isEmpty() && now - slot.lastAccess >= cache.idleTtlMillis) {
            slots.remove(key)
            return null
        }
        return slot
    }

    private fun evictionCandidate(): K? {
        if (config.retention !is StateRetention.Cache) return null
        return slots.entries.asSequence()
            .filter { it.value.listeners.isEmpty() }
            .minByOrNull { it.value.lastAccess }?.key
    }

    override fun seal(): () -> Unit {
        val listeners = synchronized(lock) {
            checkNotReducing()
            if (closed) return {}
            closed = true
            slots.values.flatMap { it.listeners }.also {
                slots.values.forEach { slot ->
                    slot.value = StateEntry.Unknown
                    slot.listeners.clear()
                }
                slots.clear()
                subscriberCount = 0
            }
        }
        return {
            listeners.forEach { it.close() }
            diagnostics.report(CommunicationDiagnostic.Closed(config.debugName))
        }
    }

    private fun checkNotReducing() {
        check(!reducing) { "A state transform must not call back into its topic" }
    }

    private fun reportSubscribers() {
        val count = synchronized(lock) { subscriberCount }
        diagnostics.report(CommunicationDiagnostic.SubscribersChanged(config.debugName, count))
    }
}
