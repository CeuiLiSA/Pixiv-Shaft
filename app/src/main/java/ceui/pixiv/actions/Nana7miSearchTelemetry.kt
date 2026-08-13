package ceui.pixiv.actions

import android.content.Context
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.loxia.Client
import ceui.loxia.Nana7miSearchTelemetryAck
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

    @JvmStatic
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val monitor = AppNetworkMonitor.get(context.applicationContext)
        val instance = ActionQueue.withRoomStore(
            context = context.applicationContext,
            databaseName = DATABASE_NAME,
            handlers = mapOf(
                ACTION_TYPE to Nana7miSearchTelemetryHandler(
                    isOnline = { monitor.isConnected },
                ),
            ),
            policy = QueuePolicy(minGapMs = MIN_GAP_MS),
            gate = { monitor.isConnected },
            // The payload carries the requester uid. A global owner lets delayed telemetry finish
            // after logout/account switching without ever borrowing the new account's Pixiv token.
            owner = { GLOBAL_OWNER },
            onError = { message, error -> Timber.tag(TAG).e(error, message) },
        )
        queue = instance
        scope.launch {
            instance.events.collect { event ->
                if (event is ActionEvent.Failed) {
                    Timber.tag(TAG).w(
                        event.cause,
                        "telemetry delivery parked event=%s reason=%s",
                        event.action.dedupeKey,
                        event.reason,
                    )
                    try {
                        val removed = instance.pruneFailed(MAX_FAILED_ROWS)
                        if (removed > 0) {
                            Timber.tag(TAG).w("telemetry dead letters pruned count=%d", removed)
                        }
                    } catch (error: Throwable) {
                        // A cleanup failure must not cancel this long-lived collector.
                        Timber.tag(TAG).w(error, "telemetry dead-letter prune failed")
                    }
                }
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
        instance.start()
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

    fun start(
        requesterUid: Long,
        contentType: ContentType,
        query: String,
        initialRoute: Route,
        initialReason: String? = null,
    ): Flow? {
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
            FlowOutcome.OFFICIAL_SUCCESS.wire,
            FlowOutcome.PREVIEW_SUCCESS.wire,
            FlowOutcome.FALLBACK_SUCCESS.wire -> payload.outcome == Outcome.SUCCESS.wire
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
                val instance = queue
                if (instance == null) {
                    Timber.tag(TAG).w("telemetry enqueue before init event_id=%s", eventId)
                    return
                }
                instance.enqueue(
                    ActionRequest(
                        type = ACTION_TYPE,
                        dedupeKey = "$ACTION_TYPE:$eventId",
                        payload = Shaft.sGson.toJson(payload),
                        coalesce = false,
                    ),
                )
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
    private const val TAG = "Nana7miTelemetry"
    private const val ACTION_TYPE = "nana7mi_search_telemetry"
    private const val DATABASE_NAME = "nana7mi_search_telemetry.db"
    private const val GLOBAL_OWNER = "nana7mi_search_telemetry"
    private const val MIN_GAP_MS = 250L
    private const val MAX_FAILED_ROWS = 500
    private const val FAILED_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1_000L
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

/** Kept outside the object to make the network dependency injectable in JVM tests. */
internal class Nana7miSearchTelemetryHandler(
    private val isOnline: () -> Boolean,
    private val report: suspend (Nana7miSearchTelemetryReq) -> Nana7miSearchTelemetryAck = {
        Client.pixshaft.reportNana7miSearchTelemetry(it)
    },
) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val parsed = action.parsePayload<Nana7miSearchTelemetry.Payload>()
            ?: return ActionOutcome.Fail("unparsable nana7mi telemetry")
        // Upgrade rows queued by the immediately preceding app build. Gson leaves newly added
        // non-present fields null because it bypasses Kotlin constructor defaults.
        val payload = parsed.copy(
            eventType = parsed.eventType ?: Nana7miSearchTelemetry.EventType.REQUEST.wire,
            appVersion = parsed.appVersion ?: "unknown",
            appChannel = parsed.appChannel ?: "unknown",
        )
        if (!Nana7miSearchTelemetry.valid(payload)) {
            return ActionOutcome.Fail("invalid nana7mi telemetry")
        }
        val outcome = try {
            val ack = report(
                Nana7miSearchTelemetryReq(
                    eventId = payload.eventId,
                    flowId = payload.flowId,
                    eventType = requireNotNull(payload.eventType),
                    occurredAt = payload.occurredAt,
                    requesterUid = payload.requesterUid,
                    borrowedUid = payload.borrowedUid,
                    query = payload.query,
                    contentType = payload.contentType,
                    page = payload.page,
                    route = payload.route,
                    outcome = payload.outcome,
                    flowOutcome = payload.flowOutcome,
                    reason = payload.reason,
                    errorType = payload.errorType,
                    httpStatus = payload.httpStatus,
                    durationMs = payload.durationMs,
                    appVersion = requireNotNull(payload.appVersion),
                    appChannel = requireNotNull(payload.appChannel),
                ),
            )
            if (ack.ok && ack.eventId == payload.eventId) {
                ActionOutcome.Success
            } else {
                ActionOutcome.Fail("nana7mi telemetry acknowledgement mismatch")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            error.toActionOutcome(isOnline())
        }
        // This dedicated queue is already isolated from business actions. ACTION scope also lets
        // later telemetry continue while one malformed/transient event backs off.
        return if (outcome is ActionOutcome.Retry) {
            outcome.copy(scope = RetryScope.ACTION)
        } else {
            outcome
        }
    }
}
