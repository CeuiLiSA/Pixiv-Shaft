package ceui.pixiv.banner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * Production [BannerManager]. Owns the [BannerQueue] and the
 * Idle ↔ Presenting state machine. All real work runs on `Main.immediate`
 * so the queue stays main-thread-confined and lock-free.
 */
class RealBannerManager(
    private val binders: Map<String, BannerViewBinder>,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    parentContext: CoroutineContext = Dispatchers.Main.immediate,
) : BannerManager {

    private val scope = CoroutineScope(SupervisorJob() + parentContext)
    private val queue = BannerQueue(maxQueueSize)

    private val _state = MutableStateFlow<BannerState>(BannerState.Idle)
    override val state: StateFlow<BannerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BannerEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<BannerEvent> = _events.asSharedFlow()

    private val _queueSize = MutableStateFlow(0)
    override val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    @Volatile
    private var isShutdown: Boolean = false

    private var started: Boolean = false
    private var autoDismissJob: Job? = null

    /**
     * Presenters currently in STARTED. Main-thread confined like [queue].
     * A request may only enter `Presenting` while this is > 0; otherwise
     * `present()` holds it back and no auto-dismiss clock is started.
     */
    private var activeHostCount: Int = 0

    override fun start() {
        check(!isShutdown) { "BannerManager is shut down" }
        if (started) {
            Timber.tag(TAG).d("start: already started, ignoring")
            return
        }
        started = true
        Timber.tag(TAG).i("BannerManager started (maxQueueSize=$maxQueueSize)")
    }

    override fun onHostStarted() {
        if (isShutdown) return
        scope.launch { handleHostStarted() }
    }

    override fun onHostStopped() {
        if (isShutdown) return
        scope.launch { handleHostStopped() }
    }

    override fun enqueue(request: BannerRequest): Boolean {
        check(!isShutdown) { "BannerManager is shut down" }
        scope.launch { handleEnqueue(request) }
        return true
    }

    override fun dismiss(id: String, reason: BannerDismissReason) {
        if (isShutdown) return
        scope.launch { handleDismiss(id, reason) }
    }

    override fun dismissCategory(category: BannerCategory) {
        check(!isShutdown) { "BannerManager is shut down" }
        scope.launch {
            val removed = queue.removeByCategory(category)
            for (r in removed) {
                _events.tryEmit(BannerEvent.Dismissed(r.id, BannerDismissReason.Programmatic))
            }
            _queueSize.value = queue.size()
            val cur = queue.currentRequest()
            if (cur != null && cur.category == category) {
                handleDismiss(cur.id, BannerDismissReason.Programmatic)
            }
        }
    }

    override fun clearAll() {
        check(!isShutdown) { "BannerManager is shut down" }
        scope.launch {
            val cleared = queue.clear()
            for (r in cleared) {
                _events.tryEmit(BannerEvent.Dismissed(r.id, BannerDismissReason.Programmatic))
            }
            _queueSize.value = 0
            val cur = queue.currentRequest()
            if (cur != null) {
                handleDismiss(cur.id, BannerDismissReason.Programmatic)
            }
        }
    }

    override fun notifyTapped(id: String) {
        if (isShutdown) return
        val deepLink = (queue.currentRequest()?.takeIf { it.id == id })?.deepLink
        _events.tryEmit(BannerEvent.Tapped(id, deepLink))
    }

    override fun notifyActionTapped(id: String, actionKey: String?) {
        if (isShutdown) return
        val actionDeepLink = (queue.currentRequest() as? BannerRequest.Text)
            ?.takeIf { it.id == id }
            ?.action
            ?.takeIf { it.actionKey == actionKey || actionKey == null }
            ?.deepLink
        _events.tryEmit(BannerEvent.Action(id = id, actionKey = actionKey, deepLink = actionDeepLink))
    }

    override fun binderFor(key: String): BannerViewBinder? = binders[key]

    override fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        autoDismissJob?.cancel()
        autoDismissJob = null
        queue.clear()
        _queueSize.value = 0
        activeHostCount = 0
        _state.value = BannerState.Shutdown
        scope.cancel()
        Timber.tag(TAG).i("BannerManager shut down")
    }

    private fun handleEnqueue(request: BannerRequest) {
        when (val outcome = queue.submit(request)) {
            BannerQueue.SubmitOutcome.Accepted -> {
                _queueSize.value = queue.size()
                advanceIfIdle()
            }
            BannerQueue.SubmitOutcome.AcceptedPreempt -> {
                val displaced = queue.currentRequest()
                if (displaced != null) {
                    cancelAutoDismiss()
                    queue.onPresentationFinished(displaced.id)
                    _events.tryEmit(BannerEvent.Dismissed(displaced.id, BannerDismissReason.Preempted))
                    queue.pushFront(displaced)
                    _queueSize.value = queue.size()
                }
                present(request)
            }
            is BannerQueue.SubmitOutcome.AcceptedWithOverflow -> {
                _events.tryEmit(BannerEvent.QueueOverflow(droppedId = outcome.evictedId))
                _events.tryEmit(BannerEvent.Dropped(outcome.evictedId, DropCause.QUEUE_FULL))
                _queueSize.value = queue.size()
                advanceIfIdle()
            }
            is BannerQueue.SubmitOutcome.Replaced -> {
                _events.tryEmit(
                    BannerEvent.Dismissed(outcome.displacedId, BannerDismissReason.Replaced(byId = request.id)),
                )
                _queueSize.value = queue.size()
                advanceIfIdle()
            }
            is BannerQueue.SubmitOutcome.ReplacedCurrent -> {
                cancelAutoDismiss()
                queue.onPresentationFinished(outcome.displacedId)
                _events.tryEmit(
                    BannerEvent.Dismissed(outcome.displacedId, BannerDismissReason.Replaced(byId = request.id)),
                )
                present(request)
            }
            is BannerQueue.SubmitOutcome.Dropped -> {
                _events.tryEmit(BannerEvent.Dropped(request.id, outcome.cause))
            }
        }
    }

    private fun handleDismiss(id: String, reason: BannerDismissReason) {
        val cur = queue.currentRequest()
        if (cur?.id == id) {
            cancelAutoDismiss()
            queue.onPresentationFinished(id)
            _events.tryEmit(BannerEvent.Dismissed(id, reason))
            advanceIfIdle()
            return
        }
        val removed = queue.remove(id)
        if (removed != null) {
            _events.tryEmit(BannerEvent.Dismissed(id, reason))
            _queueSize.value = queue.size()
        }
    }

    private fun handleHostStarted() {
        activeHostCount++
        if (activeHostCount == 1) {
            Timber.tag(TAG).d("first host started; draining held banner queue")
            advanceIfIdle()
        }
    }

    private fun handleHostStopped() {
        if (activeHostCount > 0) activeHostCount--
        if (activeHostCount == 0) {
            Timber.tag(TAG).d("all hosts stopped; new banners will be held")
        }
    }

    private fun advanceIfIdle() {
        if (queue.currentRequest() != null) return
        if (activeHostCount == 0) {
            _state.value = BannerState.Idle
            return
        }
        val next = queue.pollNext() ?: run {
            _state.value = BannerState.Idle
            return
        }
        _queueSize.value = queue.size()
        present(next)
    }

    private fun present(request: BannerRequest) {
        if (activeHostCount == 0) {
            queue.pushFront(request)
            _queueSize.value = queue.size()
            _state.value = BannerState.Idle
            Timber.tag(TAG).d("no started host; holding %s for the next one", request.id)
            return
        }
        queue.markPresenting(request)
        _state.value = BannerState.Presenting(request)
        _events.tryEmit(BannerEvent.Shown(request.id))
        scheduleAutoDismiss(request)
    }

    private fun scheduleAutoDismiss(request: BannerRequest) {
        cancelAutoDismiss()
        val timeout = request.autoDismissMillis ?: return
        autoDismissJob = scope.launch {
            delay(timeout)
            if (queue.currentRequest()?.id == request.id) {
                handleDismiss(request.id, BannerDismissReason.AutoTimeout)
            }
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    companion object {
        private const val TAG = "BannerMgr"
        const val DEFAULT_MAX_QUEUE_SIZE: Int = 32
    }
}
