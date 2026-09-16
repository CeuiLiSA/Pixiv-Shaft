package ceui.pixiv.communication

import kotlinx.coroutines.flow.Flow

/** Also implementable directly over a repository; a second in-memory store is not required. */
public fun interface StateSource<K : Any, S : Any> {
    /** Current value followed by conflated changes. Completion means this source was closed. */
    public fun observe(key: K): Flow<StateEntry<S>>
}

public sealed interface StateEntry<out S : Any> {
    public data object Unknown : StateEntry<Nothing>
    public data class Value<S : Any>(public val value: S) : StateEntry<S>
}

public interface StateWriter<K : Any, S : Any> {
    public fun set(key: K, value: S): StateWriteResult

    /**
     * Atomic per key. The transform must be short, pure, and must not call back into the topic.
     * Values and keys must be immutable, with stable equality/hash codes.
     */
    public fun update(key: K, transform: (StateEntry<S>) -> S): StateWriteResult

    /** Reset to Unknown. Existing collectors remain attached and receive future writes. */
    public fun remove(key: K): StateWriteResult
}

public enum class StateWriteResult { APPLIED, UNCHANGED, REJECTED_CAPACITY, CLOSED }

public interface StateTopic<K : Any, S : Any> {
    public val source: StateSource<K, S>
    public val writer: StateWriter<K, S>
}

public sealed interface StateRetention {
    /** Authoritative process-local state: never silently evicted. */
    public data object Retain : StateRetention

    /**
     * Rebuildable cache only. Inactive keys expire lazily on access and are LRU eviction candidates
     * at capacity. Active keys are never evicted. TTL is measured with a monotonic clock.
     */
    public data class Cache(public val idleTtlMillis: Long) : StateRetention {
        init { require(idleTtlMillis >= 0) { "idleTtlMillis must be non-negative" } }
    }
}

public data class StateTopicConfig(
    public val debugName: String,
    public val maxKeys: Int = 1_024,
    /** Total concurrent collections, including several collections of the same key. */
    public val maxSubscribers: Int = 256,
    public val retention: StateRetention = StateRetention.Retain,
) {
    init {
        require(debugName.isNotBlank()) { "debugName must not be blank" }
        require(maxKeys > 0) { "maxKeys must be positive" }
        require(maxSubscribers > 0) { "maxSubscribers must be positive" }
    }
}

public fun interface MonotonicClock {
    public fun nowMillis(): Long

    public companion object {
        public val SYSTEM: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L }
    }
}
