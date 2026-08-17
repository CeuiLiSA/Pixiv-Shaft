package ceui.pixiv.actions

import android.content.Context
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.loxia.Client
import ceui.loxia.Nana7miSearchTelemetryAck
import ceui.loxia.Nana7miSearchTelemetryBatchAck
import ceui.loxia.Nana7miSearchTelemetryBatchReq
import ceui.loxia.Nana7miSearchTelemetryReq
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.actionqueue.RetryScope
import ceui.pixiv.websocket.AppNetworkMonitor
import io.reactivex.Observable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

/**
 * Flow- and request-level telemetry for the Nana7mi borrowed-search feature.
 *
 * One [Flow] represents one user search. Its first page, fallback and later pagination share a
 * flow id; every completed request gets a separate event id. Events contain no result count,
 * next-url state, response body or account token. Delivery uses its own persistent [ActionQueue]
 * so telemetry retry/backoff cannot delay bookmark or follow actions.
 *
 * Events are buffered and delivered in batches: one flush becomes one queue row and therefore
 * one signed request carrying up to [MAX_BATCH_EVENTS] events. See [buffer] for the trade-off.
 */
internal object Nana7miSearchTelemetry {

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    Timber.tag(TAG).e(error, "telemetry scope crashed")
                },
    )

    @Volatile
    private var queue: ActionQueue? = null

    /**
     * Events waiting for the next flush, guarded by its own monitor.
     *
     * Search telemetry is bursty — one flow emits 3-5 events and every extra page adds another —
     * so one signed request per event meant a request every few hundred milliseconds while the
     * user scrolled. Buffering here and handing the durable queue ONE row per flush keeps the
     * offline/retry guarantees and collapses a whole search session into a request or two.
     *
     * The window costs durability: events still sitting here when the process dies are lost.
     * That is the same trade `HistoryReporter` makes, and it only ever loses the tail — anything
     * already flushed into the queue is persisted and retried as before.
     */
    private val buffer = ArrayDeque<Payload>()

    @JvmStatic
    fun init(context: Context) {
        if (!enabledForSecret(BuildConfig.SHAFT_EVENTS_HMAC)) {
            Timber.tag(TAG).i("telemetry disabled: SHAFT_EVENTS_HMAC is empty")
            return
        }
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        try {
            val monitor = AppNetworkMonitor.get(appContext)
            val instance = ActionQueue.withRoomStore(
                context = appContext,
                databaseName = DATABASE_NAME,
                handlers = mapOf(
                    BATCH_ACTION_TYPE to Nana7miSearchTelemetryBatchHandler(
                        isOnline = { monitor.isConnected },
                    ),
                    // Compatibility only: drains the one-event-per-row telemetry that builds
                    // before batching left in this database. Nothing enqueues this type now.
                    ACTION_TYPE to Nana7miSearchTelemetryHandler(
                        isOnline = { monitor.isConnected },
                    ),
                ),
                policy = QueuePolicy(
                    minGapMs = MIN_GAP_MS,
                    maxStoredActions = MAX_STORED_ROWS,
                ),
                gate = { monitor.isConnected },
                // The payload carries the requester uid. A global owner lets delayed telemetry
                // finish after logout/account switching without using the new Pixiv account.
                owner = { GLOBAL_OWNER },
                onError = { message, error -> Timber.tag(TAG).e(error, message) },
                onCapacityPruned = { removed ->
                    Timber.tag(TAG).w("telemetry queue capacity pruned count=%d", removed)
                },
            )
            queue = instance
            // Subscribe synchronously before the consumer starts. SharedFlow has no replay;
            // without this, a fast first failure could be emitted before cleanup is listening.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                instance.events.collect { event ->
                    if (event is ActionEvent.Failed) {
                        Timber.tag(TAG).w(
                            event.cause,
                            "telemetry delivery parked event=%s reason=%s",
                            event.action.dedupeKey,
                            event.reason,
                        )
                        try {
                            if (isPermanentFailure(event.reason)) {
                                // Bad local payloads and definitive 4xx responses cannot heal by
                                // waiting. Delete them instead of resurrecting the same poison row
                                // at every startup/connectivity change/six-hour sweep.
                                instance.forget(event.action.id)
                                Timber.tag(TAG).w(
                                    "permanent telemetry event discarded event=%s",
                                    event.action.dedupeKey,
                                )
                            } else {
                                val removed = instance.pruneFailed(MAX_FAILED_ROWS)
                                if (removed > 0) {
                                    Timber.tag(TAG).w(
                                        "telemetry dead letters pruned count=%d",
                                        removed,
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            // A cleanup failure must not cancel this long-lived collector.
                            Timber.tag(TAG).w(error, "telemetry dead-letter cleanup failed")
                        }
                    }
                }
            }
            instance.start()
            scope.launch {
                // Everything buffered before/while the queue was being assembled goes out on
                // the first tick, so events reported during startup are not dropped.
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    flushBuffer("tick")
                }
            }
            scope.launch {
                retryParked(instance, "startup")
            }
            scope.launch {
                monitor.observeConnectivity.collect { online ->
                    if (online) retryParked(instance, "network_restored")
                }
            }
            scope.launch {
                while (isActive) {
                    delay(FAILED_RETRY_INTERVAL_MS)
                    retryParked(instance, "periodic")
                }
            }
        } catch (error: Throwable) {
            // Telemetry initialization is part of Application startup. A Room/network-monitor
            // problem must never crash or brick the search process. Reset the guard and retry in
            // process; a concurrent/manual init that succeeds makes the delayed call a no-op.
            queue = null
            initialized.set(false)
            Timber.tag(TAG).e(error, "telemetry initialization failed; retrying")
            scope.launch {
                delay(INIT_RETRY_INTERVAL_MS)
                init(appContext)
            }
        }
    }

    /**
     * Buffers one event and flushes early when a burst fills [FLUSH_THRESHOLD_EVENTS], so a long
     * scroll does not wait out the whole [FLUSH_INTERVAL_MS] window.
     */
    private fun bufferEvent(payload: Payload) {
        var dropped = 0
        val flushNow = synchronized(buffer) {
            // The backend being unreachable must not turn observability into unbounded memory.
            while (buffer.size >= MAX_BUFFERED_EVENTS) {
                buffer.removeFirst()
                dropped++
            }
            buffer.addLast(payload)
            buffer.size >= FLUSH_THRESHOLD_EVENTS
        }
        if (dropped > 0) {
            Timber.tag(TAG).w("telemetry buffer overflow, dropped oldest count=%d", dropped)
        }
        if (flushNow) scope.launch { flushBuffer("threshold") }
    }

    /** Hands everything buffered to the durable queue, at most [MAX_BATCH_EVENTS] events per row. */
    private fun flushBuffer(trigger: String) {
        val instance = queue ?: return
        while (true) {
            val batch = synchronized(buffer) {
                if (buffer.isEmpty()) return
                List(minOf(buffer.size, MAX_BATCH_EVENTS)) { buffer.removeFirst() }
            }
            try {
                instance.enqueue(
                    ActionRequest(
                        type = BATCH_ACTION_TYPE,
                        // Every flush is its own row: batches share no identity, and coalescing
                        // two of them would silently discard a whole window of events.
                        dedupeKey = "$BATCH_ACTION_TYPE:${UUID.randomUUID()}",
                        payload = Shaft.sGson.toJson(BatchPayload(batch)),
                        coalesce = false,
                    ),
                )
                Timber.tag(TAG).d(
                    "telemetry batch queued trigger=%s events=%d",
                    trigger,
                    batch.size,
                )
            } catch (error: Exception) {
                // Observability must never change search success/failure behavior.
                Timber.tag(TAG).e(error, "telemetry batch enqueue failed events=%d", batch.size)
            }
        }
    }

    private suspend fun retryParked(instance: ActionQueue, trigger: String) {
        try {
            val removed = instance.pruneFailed(MAX_FAILED_ROWS)
            val retried = instance.retryAllFailed()
            Timber.tag(TAG).d(
                "telemetry recovery trigger=%s retried=%d pruned=%d",
                trigger,
                retried,
                removed,
            )
            instance.kick()
        } catch (error: Throwable) {
            Timber.tag(TAG).w(error, "telemetry recovery failed trigger=%s", trigger)
        }
    }

    private fun isPermanentFailure(reason: String): Boolean =
        reason.startsWith(PERMANENT_FAILURE_PREFIX)

    internal fun enabledForSecret(secret: String): Boolean = secret.isNotBlank()

    internal fun permanentFailure(reason: String, cause: Throwable? = null): ActionOutcome.Fail =
        ActionOutcome.Fail(PERMANENT_FAILURE_PREFIX + reason, cause)

    enum class ContentType(val wire: String) { ILLUST("illust"), NOVEL("novel") }
    enum class Page(val wire: String) { FIRST("first"), NEXT("next") }
    enum class Route(val wire: String) {
        BORROWED_OFFICIAL("borrowed_official"),
        PREVIEW_DIRECT("preview_direct"),
        PREVIEW_FALLBACK("preview_fallback"),
    }
    enum class EventType(val wire: String) {
        REQUEST("request"), FLOW_STARTED("flow_started"), FLOW_TERMINAL("flow_terminal")
    }
    enum class Outcome(val wire: String) {
        STARTED("started"), SUCCESS("success"), FAILURE("failure"), CANCELLED("cancelled")
    }
    enum class FlowOutcome(val wire: String) {
        OFFICIAL_SUCCESS("official_success"),
        PREVIEW_SUCCESS("preview_success"),
        FALLBACK_SUCCESS("fallback_success"),
        TOTAL_FAILURE("total_failure"),
        CANCELLED("cancelled"),
    }

    data class Payload(
        val eventId: String,
        val flowId: String,
        val eventType: String?,
        val occurredAt: Long,
        val requesterUid: Long,
        val borrowedUid: Long?,
        val query: String,
        val contentType: String,
        val page: String,
        val route: String,
        val outcome: String,
        val flowOutcome: String?,
        val reason: String?,
        val errorType: String?,
        val httpStatus: Int?,
        val durationMs: Long?,
        val appVersion: String?,
        val appChannel: String?,
    )

    /**
     * One queue row = one flush. [events] is nullable because Gson bypasses Kotlin constructor
     * defaults, so a truncated/foreign payload deserializes with a null list instead of failing.
     */
    data class BatchPayload(val events: List<Payload>? = null)

    fun start(
        requesterUid: Long,
        contentType: ContentType,
        query: String,
        initialRoute: Route,
        initialReason: String? = null,
    ): Flow? {
        if (!enabledForSecret(BuildConfig.SHAFT_EVENTS_HMAC)) return null
        if (requesterUid <= 0L || query.length > QUERY_MAX_CHARS) {
            Timber.tag(TAG).w(
                "telemetry flow skipped requester_uid=%d query_length=%d",
                requesterUid,
                query.length,
            )
            return null
        }
        return Flow(
            flowId = UUID.randomUUID().toString(),
            requesterUid = requesterUid,
            contentType = contentType,
            query = query,
            initialRoute = initialRoute,
            initialReason = initialReason,
        )
    }

    internal fun valid(payload: Payload, owner: String = ""): Boolean =
        UUID_RE.matches(payload.eventId) &&
                UUID_RE.matches(payload.flowId) &&
                payload.occurredAt > 0L &&
                payload.requesterUid > 0L &&
                (payload.borrowedUid == null || payload.borrowedUid > 0L) &&
                payload.query.length <= QUERY_MAX_CHARS &&
                payload.contentType in CONTENT_TYPES &&
                payload.page in PAGES &&
                payload.route in ROUTES &&
                payload.eventType != null && payload.eventType in EVENT_TYPES &&
                payload.outcome in OUTCOMES &&
                payload.flowOutcome.let { it == null || it in FLOW_OUTCOMES } &&
                (payload.reason == null || payload.reason.length <= LABEL_MAX_CHARS) &&
                (payload.errorType == null || payload.errorType.length <= LABEL_MAX_CHARS) &&
                (payload.httpStatus == null || payload.httpStatus in 100..599) &&
                (payload.durationMs == null || payload.durationMs in 0..MAX_DURATION_MS) &&
                payload.appVersion?.isNotBlank() == true && payload.appVersion.length <= VERSION_MAX_CHARS &&
                payload.appChannel?.isNotBlank() == true && payload.appChannel.length <= VERSION_MAX_CHARS &&
                validCombination(payload) &&
                (owner.isBlank() || owner == payload.requesterUid.toString())

    private fun validCombination(payload: Payload): Boolean = when (payload.eventType) {
        EventType.REQUEST.wire ->
            payload.outcome != Outcome.STARTED.wire &&
                    payload.flowOutcome == null && payload.durationMs == null &&
                    (payload.route != Route.BORROWED_OFFICIAL.wire || payload.borrowedUid != null)
        EventType.FLOW_STARTED.wire ->
            payload.outcome == Outcome.STARTED.wire &&
                    payload.flowOutcome == null && payload.durationMs == null
        EventType.FLOW_TERMINAL.wire -> when (payload.flowOutcome) {
            FlowOutcome.CANCELLED.wire -> payload.outcome == Outcome.CANCELLED.wire
            FlowOutcome.TOTAL_FAILURE.wire -> payload.outcome == Outcome.FAILURE.wire
            FlowOutcome.OFFICIAL_SUCCESS.wire ->
                payload.outcome == Outcome.SUCCESS.wire &&
                        payload.route == Route.BORROWED_OFFICIAL.wire &&
                        payload.borrowedUid != null
            FlowOutcome.PREVIEW_SUCCESS.wire ->
                payload.outcome == Outcome.SUCCESS.wire &&
                        payload.route == Route.PREVIEW_DIRECT.wire
            FlowOutcome.FALLBACK_SUCCESS.wire ->
                payload.outcome == Outcome.SUCCESS.wire &&
                        payload.route == Route.PREVIEW_FALLBACK.wire
            else -> false
        } && payload.durationMs != null
        else -> false
    }

    class Flow internal constructor(
        private val flowId: String,
        private val requesterUid: Long,
        private val contentType: ContentType,
        private val query: String,
        initialRoute: Route,
        initialReason: String?,
    ) {
        @Volatile
        private var startedAt = 0L
        private val started = AtomicBoolean(false)
        private val terminal = AtomicBoolean(false)

        @Volatile
        private var currentRoute = initialRoute

        @Volatile
        private var currentReason = initialReason

        @Volatile
        private var currentBorrowedUid: Long? = null

        fun borrowed(uid: Long) {
            if (uid > 0L) currentBorrowedUid = uid
        }

        fun fallback(reason: String) {
            currentRoute = Route.PREVIEW_FALLBACK
            currentReason = reason.take(LABEL_MAX_CHARS)
        }

        fun <T : Any> track(
            source: Observable<T>,
            page: Page,
            route: Route = currentRoute,
            borrowedUid: Long? = currentBorrowedUid,
            reason: String? = if (route == currentRoute) currentReason else null,
        ): Observable<T> {
            return Observable.defer {
                val completed = AtomicBoolean(false)
                source
                    .doOnNext {
                        if (completed.compareAndSet(false, true)) {
                            report(EventType.REQUEST, page, route, Outcome.SUCCESS, null, borrowedUid, reason, null, null)
                        }
                    }
                    .doOnError { error ->
                        if (completed.compareAndSet(false, true)) {
                            report(EventType.REQUEST, page, route, Outcome.FAILURE, null, borrowedUid, reason, error, null)
                        }
                    }
                    .doOnDispose {
                        if (completed.compareAndSet(false, true)) {
                            report(EventType.REQUEST, page, route, Outcome.CANCELLED, null, borrowedUid, "disposed", null, null)
                        }
                    }
            }
        }

        /** Wraps only the first-page observable; pagination remains request-level telemetry. */
        fun <T : Any> observeFirst(source: Observable<T>): Observable<T> = source
            .doOnSubscribe {
                if (started.compareAndSet(false, true)) {
                    startedAt = monotonicNowMs()
                    report(
                        EventType.FLOW_STARTED,
                        Page.FIRST,
                        currentRoute,
                        Outcome.STARTED,
                        null,
                        currentBorrowedUid,
                        currentReason,
                        null,
                        null,
                    )
                }
            }
            .doOnNext { finish(successOutcome(), Outcome.SUCCESS, null) }
            .doOnError { error -> finish(FlowOutcome.TOTAL_FAILURE, Outcome.FAILURE, error) }
            .doOnDispose { finish(FlowOutcome.CANCELLED, Outcome.CANCELLED, null) }

        private fun successOutcome(): FlowOutcome = when (currentRoute) {
            Route.BORROWED_OFFICIAL -> FlowOutcome.OFFICIAL_SUCCESS
            Route.PREVIEW_DIRECT -> FlowOutcome.PREVIEW_SUCCESS
            Route.PREVIEW_FALLBACK -> FlowOutcome.FALLBACK_SUCCESS
        }

        private fun finish(flowOutcome: FlowOutcome, outcome: Outcome, error: Throwable?) {
            if (!terminal.compareAndSet(false, true)) return
            report(
                EventType.FLOW_TERMINAL,
                Page.FIRST,
                currentRoute,
                outcome,
                flowOutcome,
                currentBorrowedUid,
                currentReason,
                error,
                (monotonicNowMs() - startedAt).coerceAtLeast(0L),
            )
        }

        private fun report(
            eventType: EventType,
            page: Page,
            route: Route,
            outcome: Outcome,
            flowOutcome: FlowOutcome?,
            borrowedUid: Long?,
            reason: String?,
            error: Throwable?,
            durationMs: Long?,
        ) {
            try {
                val http = error?.let { causeChain(it).filterIsInstance<HttpException>().firstOrNull() }
                val root = error?.let { causeChain(it).last() }
                val effectiveReason = reason ?: when {
                    http != null -> "http_${http.code()}"
                    root is IOException -> "io_failure"
                    error != null -> "unexpected_failure"
                    else -> null
                }
                val eventId = UUID.randomUUID().toString()
                val payload = Payload(
                    eventId = eventId,
                    flowId = flowId,
                    eventType = eventType.wire,
                    occurredAt = System.currentTimeMillis(),
                    requesterUid = requesterUid,
                    borrowedUid = borrowedUid,
                    query = query,
                    contentType = contentType.wire,
                    page = page.wire,
                    route = route.wire,
                    outcome = outcome.wire,
                    flowOutcome = flowOutcome?.wire,
                    reason = effectiveReason?.take(LABEL_MAX_CHARS),
                    errorType = error?.javaClass?.simpleName?.take(LABEL_MAX_CHARS),
                    httpStatus = http?.code(),
                    durationMs = durationMs,
                    appVersion = APP_VERSION,
                    appChannel = BuildConfig.UPDATE_CHANNEL,
                )
                if (!valid(payload, requesterUid.toString())) return
                // Buffering (instead of enqueueing here) also means events reported before the
                // queue finished initializing are kept rather than dropped.
                bufferEvent(payload)
            } catch (reportError: Exception) {
                // Observability must never change search success/failure behavior.
                Timber.tag(TAG).e(reportError, "nana7mi telemetry enqueue failed")
            }
        }
    }

    private fun causeChain(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    internal const val QUERY_MAX_CHARS = 1_000
    private const val LABEL_MAX_CHARS = 100
    private const val VERSION_MAX_CHARS = 50
    private const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L
    internal const val TAG = "Nana7miTelemetry"
    private const val ACTION_TYPE = "nana7mi_search_telemetry"
    internal const val BATCH_ACTION_TYPE = "nana7mi_search_telemetry_batch"
    private const val DATABASE_NAME = "nana7mi_search_telemetry.db"
    private const val GLOBAL_OWNER = "nana7mi_search_telemetry"
    private const val MIN_GAP_MS = 250L
    /** Flush cadence; a whole search session usually leaves as one or two requests. */
    private const val FLUSH_INTERVAL_MS = 30_000L
    /** Flush early on a burst instead of making the tail of it wait a full window. */
    private const val FLUSH_THRESHOLD_EVENTS = 20
    /** Events per request. Must stay <= the server's NANA7MI_TELEMETRY_BATCH_MAX_EVENTS. */
    private const val MAX_BATCH_EVENTS = 50
    /** Ceiling for events not yet handed to the queue; oldest lose. */
    private const val MAX_BUFFERED_EVENTS = 200
    // Rows are batches now, so these caps are an order of magnitude smaller than the
    // one-row-per-event era while holding far more events (500 x 50 = 25,000).
    private const val MAX_FAILED_ROWS = 200
    private const val MAX_STORED_ROWS = 500
    private const val INIT_RETRY_INTERVAL_MS = 60_000L
    private const val FAILED_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    private const val PERMANENT_FAILURE_PREFIX = "permanent telemetry failure: "
    private val APP_VERSION = if (BuildConfig.IS_DEBUG_MODE) {
        BuildConfig.VERSION_NAME + "-debug"
    } else {
        BuildConfig.VERSION_NAME
    }
    private val UUID_RE = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )
    private val CONTENT_TYPES = ContentType.entries.mapTo(hashSetOf()) { it.wire }
    private val PAGES = Page.entries.mapTo(hashSetOf()) { it.wire }
    private val ROUTES = Route.entries.mapTo(hashSetOf()) { it.wire }
    private val EVENT_TYPES = EventType.entries.mapTo(hashSetOf()) { it.wire }
    private val OUTCOMES = Outcome.entries.mapTo(hashSetOf()) { it.wire }
    private val FLOW_OUTCOMES = FlowOutcome.entries.mapTo(hashSetOf()) { it.wire }
}

/**
 * Delivers one buffered flush. Kept outside the object to make the network dependency
 * injectable in JVM tests.
 */
internal class Nana7miSearchTelemetryBatchHandler(
    private val isOnline: () -> Boolean,
    private val report: suspend (Nana7miSearchTelemetryBatchReq) -> Nana7miSearchTelemetryBatchAck = {
        Client.pixshaft.reportNana7miSearchTelemetryBatch(it)
    },
) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val parsed = action.parsePayload<Nana7miSearchTelemetry.BatchPayload>()
        val stored = parsed?.events
            ?: return Nana7miSearchTelemetry.permanentFailure("unparsable batch payload")
        // Drop only the individually bad events: the server does the same, and failing the
        // whole row would throw away every healthy event that was flushed next to it.
        val events = stored.map { it.upgraded() }.filter { Nana7miSearchTelemetry.valid(it) }
        val dropped = stored.size - events.size
        if (dropped > 0) {
            Timber.tag(Nana7miSearchTelemetry.TAG).w("dropped %d invalid events from batch", dropped)
        }
        if (events.isEmpty()) {
            return Nana7miSearchTelemetry.permanentFailure("no valid events in batch")
        }
        val outcome = try {
            val ack = report(Nana7miSearchTelemetryBatchReq(events.map { it.toRequest() }))
            if (ack.rejected > 0) {
                // Our validator and the server's disagree — retrying cannot fix that, so leave
                // a trace instead of parking the row forever.
                Timber.tag(Nana7miSearchTelemetry.TAG).w(
                    "server rejected %d of %d batched events",
                    ack.rejected,
                    events.size,
                )
            }
            if (ack.ok) {
                ActionOutcome.Success
            } else {
                // A 2xx that does not acknowledge the batch points at a backend or rollout
                // problem, not bad event data. Park and periodically retry it.
                ActionOutcome.Retry(
                    cause = IllegalStateException("nana7mi telemetry batch not acknowledged"),
                    scope = RetryScope.ACTION,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            error.toTelemetryActionOutcome(isOnline())
        }
        return outcome
    }
}

/**
 * Upgrades a row queued by an older app build: Gson leaves fields that did not exist then null
 * because it bypasses Kotlin constructor defaults.
 */
private fun Nana7miSearchTelemetry.Payload.upgraded(): Nana7miSearchTelemetry.Payload = copy(
    eventType = eventType ?: Nana7miSearchTelemetry.EventType.REQUEST.wire,
    appVersion = appVersion ?: "unknown",
    appChannel = appChannel ?: "unknown",
)

/** Only call on a payload that passed [Nana7miSearchTelemetry.valid]. */
private fun Nana7miSearchTelemetry.Payload.toRequest(): Nana7miSearchTelemetryReq =
    Nana7miSearchTelemetryReq(
        eventId = eventId,
        flowId = flowId,
        eventType = requireNotNull(eventType),
        occurredAt = occurredAt,
        requesterUid = requesterUid,
        borrowedUid = borrowedUid,
        query = query,
        contentType = contentType,
        page = page,
        route = route,
        outcome = outcome,
        flowOutcome = flowOutcome,
        reason = reason,
        errorType = errorType,
        httpStatus = httpStatus,
        durationMs = durationMs,
        appVersion = requireNotNull(appVersion),
        appChannel = requireNotNull(appChannel),
    )

/**
 * Drains one-event-per-row telemetry left by builds from before batching. Nothing enqueues this
 * type any more; the batch handler above is the live path.
 */
internal class Nana7miSearchTelemetryHandler(
    private val isOnline: () -> Boolean,
    private val report: suspend (Nana7miSearchTelemetryReq) -> Nana7miSearchTelemetryAck = {
        Client.pixshaft.reportNana7miSearchTelemetry(it)
    },
) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val parsed = action.parsePayload<Nana7miSearchTelemetry.Payload>()
            ?: return Nana7miSearchTelemetry.permanentFailure("unparsable payload")
        val payload = parsed.upgraded()
        if (!Nana7miSearchTelemetry.valid(payload)) {
            return Nana7miSearchTelemetry.permanentFailure("invalid payload")
        }
        val outcome = try {
            val ack = report(payload.toRequest())
            if (ack.ok && ack.eventId == payload.eventId) {
                ActionOutcome.Success
            } else {
                // A valid 2xx with a mismatched/negative acknowledgement points to a backend or
                // rollout problem, not bad event data. Park and periodically retry it.
                ActionOutcome.Retry(
                    cause = IllegalStateException("nana7mi telemetry acknowledgement mismatch"),
                    scope = RetryScope.ACTION,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            error.toTelemetryActionOutcome(isOnline())
        }
        return outcome
    }
}

/** Error policy for the pixshaft telemetry API; Pixiv token semantics do not apply here. */
private fun Throwable.toTelemetryActionOutcome(isOnline: Boolean): ActionOutcome = when (this) {
    is HttpException -> when (val code = code()) {
        408 -> ActionOutcome.Retry(cause = this, scope = RetryScope.ACTION)
        // Auth and route availability can change across an app/API rollout. Preserve these in the
        // bounded dead-letter queue so an updated secret or newly deployed route can recover them.
        401, 403, 404 -> ActionOutcome.Retry(cause = this, scope = RetryScope.QUEUE)
        429 -> ActionOutcome.Retry(
            retryAfterMs = response()?.headers()?.get("Retry-After")
                ?.trim()
                ?.toLongOrNull()
                ?.times(1_000L),
            cause = this,
            scope = RetryScope.QUEUE,
        )
        // The telemetry queue is physically isolated from bookmark/follow actions, so systemic
        // backend errors should cool this whole queue instead of letting every later row hammer it.
        in 500..599 -> ActionOutcome.Retry(cause = this, scope = RetryScope.QUEUE)
        else -> Nana7miSearchTelemetry.permanentFailure("HTTP $code", this)
    }
    is IOException -> ActionOutcome.Retry(
        cause = this,
        scope = if (isOnline) RetryScope.ACTION else RetryScope.QUEUE,
        countsAsAttempt = isOnline,
    )
    // Converter/protocol errors can be caused by a bad backend rollout. Keep a bounded dead
    // letter and let the six-hour recovery sweep retry after the server has been repaired.
    else -> ActionOutcome.Retry(cause = this, scope = RetryScope.ACTION)
}
