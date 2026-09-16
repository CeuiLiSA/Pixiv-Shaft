package ceui.pixiv.communication

/** Metadata only: never includes payloads. Calls occur outside internal locks; do not block. */
public fun interface CommunicationObserver {
    public fun onDiagnostic(diagnostic: CommunicationDiagnostic)
}

public sealed interface CommunicationDiagnostic {
    public val topic: String

    public data class Publication(
        override val topic: String,
        public val result: PublishResult,
    ) : CommunicationDiagnostic

    public data class SubscribersChanged(
        override val topic: String,
        public val count: Int,
    ) : CommunicationDiagnostic

    public data class Closed(override val topic: String) : CommunicationDiagnostic
}

public class SubscriptionCapacityException(public val topicName: String) :
    IllegalStateException("Subscription capacity exceeded for $topicName")

public class ScopeClosedException : IllegalStateException("Communication scope is closed")
