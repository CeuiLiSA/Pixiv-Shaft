package ceui.pixiv.communication

import kotlinx.coroutines.flow.Flow

/** Transient multicast. Each collection is independent; late collectors receive no history. */
public interface EventSource<E : Any> {
    public val events: Flow<E>
}

public interface EventPublisher<E : Any> {
    /**
     * Accept a broadcast atomically for all current subscribers, or wait/reject per policy.
     * Cancellation may race acceptance: do not blindly retry a cancelled non-idempotent operation.
     * A successful return does not acknowledge delivery or processing.
     */
    public suspend fun publish(event: E): PublishResult

    /**
     * Non-suspending admission. Never launches a coroutine or waits for mailbox capacity.
     * Consumers choose their context: Unconfined/immediate dispatchers can run on the notifying
     * thread. No application handler runs under the mailbox lock. Dispatch expensive work away
     * from UI/callback threads in the consumer.
     */
    public fun tryPublish(event: E): PublishResult
}

public enum class PublishResult {
    /** No subscribers is also accepted, with no retained message. */
    Accepted,
    /** Nothing was queued for any subscriber. */
    RejectedFull,
    Closed,
}

/** The composition root owns this handle; inject its individual capabilities into consumers. */
public interface EventTopic<E : Any> {
    public val source: EventSource<E>
    public val publisher: EventPublisher<E>
}

public enum class OverflowPolicy { Suspend, RejectNew }

public data class EventTopicConfig(
    public val debugName: String,
    /** Per subscriber, excluding one event currently executing in its handler. */
    public val bufferCapacity: Int = 64,
    public val maxSubscribers: Int = 64,
    public val overflow: OverflowPolicy = OverflowPolicy.RejectNew,
) {
    init {
        require(debugName.isNotBlank()) { "debugName must not be blank" }
        require(bufferCapacity in 1..65_536) { "bufferCapacity must be in 1..65536" }
        require(maxSubscribers in 1..65_536) { "maxSubscribers must be in 1..65536" }
    }
}
