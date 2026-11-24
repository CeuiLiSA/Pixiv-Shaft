package ceui.pixiv.nana7mi.session

import ceui.pixiv.nana7mi.session.internal.BoundedNetworkRetry
import ceui.pixiv.nana7mi.session.internal.DefaultBorrowedSession
import ceui.pixiv.nana7mi.session.internal.DefaultSerialExecutionCoordinator
import ceui.pixiv.nana7mi.session.internal.SafeSessionObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Immutable configuration for a [Nana7miSessionEngine].
 *
 * Construction performs no I/O and spawns no coroutines or threads. Use [Builder] / [newBuilder]
 * to preserve binary compatibility across future SDK releases.
 */
public class Nana7miSessionConfig<A : Any> private constructor(
    public val dispatcher: AccountDispatcher<A>,
    public val tokenRefresher: AccountTokenRefresher,
    public val payloadUpdater: AccountPayloadUpdater<A>,
    public val reportStore: AccountReportStore<A>,
    public val authFailureClassifier: AuthFailureClassifier,
    public val accessPolicy: SessionAccessPolicy,
    public val retryPolicy: NetworkRetryPolicy,
    public val transportFailureClassifier: TransportFailureClassifier,
    public val requestIdGenerator: RequestIdGenerator,
    public val clock: SessionClock,
    public val observer: SessionObserver,
    public val tokenValidDurationMs: Long,
    public val ioDispatcher: CoroutineDispatcher,
) {
    /** Creates a mutable [Builder] copied from this configuration. */
    public fun newBuilder(): Builder<A> = Builder<A>()
        .dispatcher(dispatcher)
        .tokenRefresher(tokenRefresher)
        .payloadUpdater(payloadUpdater)
        .reportStore(reportStore)
        .authFailureClassifier(authFailureClassifier)
        .accessPolicy(accessPolicy)
        .retryPolicy(retryPolicy)
        .transportFailureClassifier(transportFailureClassifier)
        .requestIdGenerator(requestIdGenerator)
        .clock(clock)
        .observer(observer)
        .tokenValidDurationMs(tokenValidDurationMs)
        .ioDispatcher(ioDispatcher)

    @Nana7miDsl
    public class Builder<A : Any> {
        public var dispatcher: AccountDispatcher<A>? = null
        public var tokenRefresher: AccountTokenRefresher? = null
        public var payloadUpdater: AccountPayloadUpdater<A>? = null
        public var reportStore: AccountReportStore<A>? = null
        public var authFailureClassifier: AuthFailureClassifier = AuthFailureClassifier.NEVER
        public var accessPolicy: SessionAccessPolicy = SessionAccessPolicy.ALWAYS_ALLOWED
        public var retryPolicy: NetworkRetryPolicy = NetworkRetryPolicy.DEFAULT
        public var transportFailureClassifier: TransportFailureClassifier = TransportFailureClassifier.DEFAULT
        public var requestIdGenerator: RequestIdGenerator = RequestIdGenerator.UUID
        public var clock: SessionClock = SessionClock.SYSTEM
        public var observer: SessionObserver = SessionObserver.NO_OP
        public var tokenValidDurationMs: Long = DEFAULT_TOKEN_VALID_DURATION_MS
        public var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        public fun dispatcher(dispatcher: AccountDispatcher<A>): Builder<A> = apply { this.dispatcher = dispatcher }
        public fun tokenRefresher(refresher: AccountTokenRefresher): Builder<A> = apply { this.tokenRefresher = refresher }
        public fun payloadUpdater(updater: AccountPayloadUpdater<A>): Builder<A> = apply { this.payloadUpdater = updater }
        public fun reportStore(store: AccountReportStore<A>): Builder<A> = apply { this.reportStore = store }
        public fun authFailureClassifier(classifier: AuthFailureClassifier): Builder<A> = apply { this.authFailureClassifier = classifier }
        public fun accessPolicy(policy: SessionAccessPolicy): Builder<A> = apply { this.accessPolicy = policy }
        public fun retryPolicy(policy: NetworkRetryPolicy): Builder<A> = apply { this.retryPolicy = policy }
        public fun transportFailureClassifier(classifier: TransportFailureClassifier): Builder<A> = apply { this.transportFailureClassifier = classifier }
        public fun requestIdGenerator(generator: RequestIdGenerator): Builder<A> = apply { this.requestIdGenerator = generator }
        public fun clock(clock: SessionClock): Builder<A> = apply { this.clock = clock }
        public fun observer(observer: SessionObserver): Builder<A> = apply { this.observer = observer }
        public fun tokenValidDurationMs(ms: Long): Builder<A> = apply { this.tokenValidDurationMs = ms }
        public fun ioDispatcher(dispatcher: CoroutineDispatcher): Builder<A> = apply { this.ioDispatcher = dispatcher }

        public fun build(): Nana7miSessionConfig<A> {
            require(tokenValidDurationMs > 0L) { "tokenValidDurationMs must be positive" }
            return Nana7miSessionConfig(
                dispatcher = requireNotNull(dispatcher) { "dispatcher must be configured" },
                tokenRefresher = requireNotNull(tokenRefresher) { "tokenRefresher must be configured" },
                payloadUpdater = requireNotNull(payloadUpdater) { "payloadUpdater must be configured" },
                reportStore = requireNotNull(reportStore) { "reportStore must be configured" },
                authFailureClassifier = authFailureClassifier,
                accessPolicy = accessPolicy,
                retryPolicy = retryPolicy,
                transportFailureClassifier = transportFailureClassifier,
                requestIdGenerator = requestIdGenerator,
                clock = clock,
                observer = observer,
                tokenValidDurationMs = tokenValidDurationMs,
                ioDispatcher = ioDispatcher,
            )
        }
    }

    public companion object {
        /** Default local renewal horizon matching a 55-minute freshness classification. */
        public const val DEFAULT_TOKEN_VALID_DURATION_MS: Long = 55L * 60_000L

        @JvmStatic
        public fun <A : Any> builder(): Builder<A> = Builder()

        public inline fun <A : Any> create(block: Builder<A>.() -> Unit): Nana7miSessionConfig<A> =
            Builder<A>().apply(block).build()
    }
}

/**
 * Process-scoped (or test-scoped) factory and coordinator for [BorrowedSession] instances.
 *
 * Owns one fair [serialCoordinator] and one [retryExecutor] shared by every session spawned from
 * [newSession]. Contains zero global mutable state, performs no I/O during construction, and has
 * zero Android dependencies.
 *
 * Implements [AutoCloseable]: closing the engine prevents spawning new sessions (throwing
 * [SessionEngineClosedException]) and short-circuits subsequent acquisitions with
 * [AcquireResult.Closed], while allowing already-running token persistence to unwind cleanly.
 * A process-level owner may retain one instance for the lifetime of the process.
 */
public class Nana7miSessionEngine<A : Any> @JvmOverloads public constructor(
    public val config: Nana7miSessionConfig<A>,
    serialCoordinator: SerialExecutionCoordinator? = null,
) : AutoCloseable {

    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val safeObserver: SafeSessionObserver = SafeSessionObserver(config.observer)

    /** `true` once [close] has been invoked on this engine. */
    public val isClosed: Boolean
        get() = closed.get()

    /** Shared bounded-budget network retry executor configured from [config]. */
    public val retryExecutor: NetworkRetryExecutor = BoundedNetworkRetry(
        defaultPolicy = config.retryPolicy,
        classifier = config.transportFailureClassifier,
        clock = config.clock,
        observer = safeObserver,
    )

    /** Shared fair serial permit coordinator used by all sessions spawned from this engine. */
    public val serialCoordinator: SerialExecutionCoordinator = serialCoordinator
        ?: DefaultSerialExecutionCoordinator(
            ioDispatcher = config.ioDispatcher,
            clock = config.clock,
            observer = safeObserver,
        )

    /**
     * Spawns a fresh, generation-isolated [BorrowedSession] starting in [BorrowedSessionState.Idle].
     *
     * @throws SessionEngineClosedException if this engine has been closed.
     */
    public fun newSession(): BorrowedSession<A> {
        if (closed.get()) throw SessionEngineClosedException()
        return DefaultBorrowedSession(
            config = config,
            serialCoordinator = serialCoordinator,
            retryExecutor = retryExecutor,
            observer = safeObserver,
            isEngineClosed = closed::get,
        )
    }

    /**
     * Idempotently closes this engine. New calls to [newSession] throw [SessionEngineClosedException]
     * and new acquisition attempts on existing sessions return [AcquireResult.Closed] without
     * contacting the network. In-flight guarded token rotations and `NonCancellable` persistence
     * are allowed to unwind naturally.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            safeObserver.emit(SessionEvent.EngineClosed)
        }
    }

    /** Fluent builder for Kotlin DSL and Java callers. */
    @Nana7miDsl
    public class Builder<A : Any> {
        private val configBuilder: Nana7miSessionConfig.Builder<A> = Nana7miSessionConfig.Builder()
        public var serialCoordinator: SerialExecutionCoordinator? = null

        public var dispatcher: AccountDispatcher<A>?
            get() = configBuilder.dispatcher
            set(value) { configBuilder.dispatcher = value }

        public var tokenRefresher: AccountTokenRefresher?
            get() = configBuilder.tokenRefresher
            set(value) { configBuilder.tokenRefresher = value }

        public var payloadUpdater: AccountPayloadUpdater<A>?
            get() = configBuilder.payloadUpdater
            set(value) { configBuilder.payloadUpdater = value }

        public var reportStore: AccountReportStore<A>?
            get() = configBuilder.reportStore
            set(value) { configBuilder.reportStore = value }

        public var authFailureClassifier: AuthFailureClassifier
            get() = configBuilder.authFailureClassifier
            set(value) { configBuilder.authFailureClassifier = value }

        public var accessPolicy: SessionAccessPolicy
            get() = configBuilder.accessPolicy
            set(value) { configBuilder.accessPolicy = value }

        public var retryPolicy: NetworkRetryPolicy
            get() = configBuilder.retryPolicy
            set(value) { configBuilder.retryPolicy = value }

        public var transportFailureClassifier: TransportFailureClassifier
            get() = configBuilder.transportFailureClassifier
            set(value) { configBuilder.transportFailureClassifier = value }

        public var requestIdGenerator: RequestIdGenerator
            get() = configBuilder.requestIdGenerator
            set(value) { configBuilder.requestIdGenerator = value }

        public var clock: SessionClock
            get() = configBuilder.clock
            set(value) { configBuilder.clock = value }

        public var observer: SessionObserver
            get() = configBuilder.observer
            set(value) { configBuilder.observer = value }

        public var tokenValidDurationMs: Long
            get() = configBuilder.tokenValidDurationMs
            set(value) { configBuilder.tokenValidDurationMs = value }

        public var ioDispatcher: CoroutineDispatcher
            get() = configBuilder.ioDispatcher
            set(value) { configBuilder.ioDispatcher = value }

        public fun dispatcher(dispatcher: AccountDispatcher<A>): Builder<A> = apply { this.dispatcher = dispatcher }
        public fun tokenRefresher(refresher: AccountTokenRefresher): Builder<A> = apply { this.tokenRefresher = refresher }
        public fun payloadUpdater(updater: AccountPayloadUpdater<A>): Builder<A> = apply { this.payloadUpdater = updater }
        public fun reportStore(store: AccountReportStore<A>): Builder<A> = apply { this.reportStore = store }
        public fun authFailureClassifier(classifier: AuthFailureClassifier): Builder<A> = apply { this.authFailureClassifier = classifier }
        public fun accessPolicy(policy: SessionAccessPolicy): Builder<A> = apply { this.accessPolicy = policy }
        public fun retryPolicy(policy: NetworkRetryPolicy): Builder<A> = apply { this.retryPolicy = policy }
        public fun transportFailureClassifier(classifier: TransportFailureClassifier): Builder<A> = apply { this.transportFailureClassifier = classifier }
        public fun requestIdGenerator(generator: RequestIdGenerator): Builder<A> = apply { this.requestIdGenerator = generator }
        public fun clock(clock: SessionClock): Builder<A> = apply { this.clock = clock }
        public fun observer(observer: SessionObserver): Builder<A> = apply { this.observer = observer }
        public fun tokenValidDurationMs(ms: Long): Builder<A> = apply { this.tokenValidDurationMs = ms }
        public fun ioDispatcher(dispatcher: CoroutineDispatcher): Builder<A> = apply { this.ioDispatcher = dispatcher }
        public fun serialCoordinator(coordinator: SerialExecutionCoordinator?): Builder<A> = apply { this.serialCoordinator = coordinator }

        public fun build(): Nana7miSessionEngine<A> = Nana7miSessionEngine(
            config = configBuilder.build(),
            serialCoordinator = serialCoordinator,
        )
    }

    public companion object {
        @JvmStatic
        public fun <A : Any> builder(): Builder<A> = Builder()

        public inline fun <A : Any> create(block: Builder<A>.() -> Unit): Nana7miSessionEngine<A> =
            Builder<A>().apply(block).build()
    }
}
