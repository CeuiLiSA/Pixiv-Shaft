package ceui.pixiv.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatReplyRef
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.base.LoadReason
import ceui.pixiv.chat.base.PageState
import ceui.pixiv.chat.base.PagingState
import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.ChatMessageStore
import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Local-first chat list ViewModel for shaft-api-v2 (uid-routing protocol).
 *
 * ```
 * RecyclerView ← observe ← [messages] ← Room Flow ← ChatMessageStore
 *                                            ↑ UPSERT (by localKey)
 *                            ┌───────────────┤
 *                            │               │
 *                  ChatHistorySource    ChatMessageStream (filtered by room)
 *                   (HTTP pagination)    (WS broadcast)
 * ```
 *
 * UI **only** reads [messages] (Room Flow). All writes — optimistic-send,
 * WS echo, history backfill — UPSERT by `localKey`. Doc §4 / §9.2.
 *
 * ## Room derivation
 *
 * - `toUid == null` → [room] = `"global"`
 * - `toUid != null` → [room] = `ChatThreadId.oneOnOneThreadId(selfUid, toUid)`
 *
 * The stream subscriber filters by `frame.room == room`, so a fragment
 * scoped to the global room never sees 1v1 traffic and vice versa.
 *
 * ## Send (doc §3.1 + §4.3)
 *
 * 1. Generate UUIDv4 `client_msg_id`
 * 2. Optimistic UPSERT local row `state=Sending`
 * 3. Build `msg` frame (`msgGlobal` or `msg1v1`) and dispatch via [sender]
 * 4. WS broadcast echo arrives → stream emits the same row → UPSERT
 *    keyed on the same localKey → `state=Delivered`
 * 5. On WS dispatch rejection → mark local row `state=Failed`
 */
class ChatListViewModel(
    val selfUid: Long,
    /** `null` = global broadcast room; non-null = peer pixiv uid for 1v1. */
    val toUid: Long?,
    private val store: ChatMessageStore<ChatMessageEntity>,
    private val historySource: ChatHistorySource<ChatMessageEntity>,
    private val stream: ChatMessageStream<ChatMessageEntity>,
    private val sender: WsMsgSender,
    private val typingSender: WsTypingSender,
    /**
     * App-scoped flow of `typing` frames (every room — VM filters by [room]).
     * Provided by [ceui.pixiv.chat.api.ShaftChatGateway.typingFrames]; tests
     * pass an in-memory flow. `null`-routed VMs (global room) never read it.
     */
    private val typingFrames: Flow<ChatFrame.Typing>,
    private val pageSize: Int = 30,
) : ViewModel() {

    /** Derived from `(selfUid, toUid)` per doc §3.2. Single source of truth for the filter. */
    val room: String = if (toUid == null) ChatThreadId.ROOM_GLOBAL
                       else ChatThreadId.oneOnOneThreadId(selfUid, toUid)

    // ── Page state (loading / error / empty / content-ready) ────────────

    private val _pageState = MutableStateFlow<PageState<Unit>>(PageState.Loading())
    val pageState: StateFlow<PageState<Unit>> = _pageState.asStateFlow()

    // ── Message list (from local store) ─────────────────────────────────

    private val windowSize = MutableStateFlow(pageSize)

    val messages: StateFlow<List<ChatMessageEntity>> = windowSize
        .flatMapLatest { limit ->
            Timber.tag(TAG_PERF).d("flatMapLatest: re-subscribe room=%s limit=%d", room, limit)
            store.observe(room, limit)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Paging state (for loading older history) ────────────────────────

    private val _pagingState = MutableStateFlow<PagingState>(PagingState.Idle)
    val pagingState: StateFlow<PagingState> = _pagingState.asStateFlow()

    // ── Internal ────────────────────────────────────────────────────────

    private var nextPageToken: String? = null
    private var firstPageLoaded = false
    private var pagingJob: Job? = null
    private var syncJob: Job? = null

    /**
     * Self-sent messages awaiting their broadcast echo. Key = `clientMsgId`,
     * value = elapsed-realtime nanos at the optimistic-write moment. When
     * the matching WS echo flows through [startLiveSync] we read this entry
     * to log the round-trip latency — a direct ground-truth signal of WS
     * health (≈ network RTT + server queue depth).
     */
    private val inFlightSends = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Wall-clock ms when we last sent a `typing` start frame. Acts as a
     * lightweight per-VM debounce: subsequent [notifyTyping] calls within
     * [TYPING_REFRESH_INTERVAL_MS] are suppressed locally so we don't
     * burn the server-side bucket (10 frames / 10s) on every keystroke.
     * `0L` = "haven't typed yet, or just stopped" — also the gate that
     * makes [notifyTypingStop] cheap when called on a quiet input.
     */
    private var lastTypingStartSentMs = 0L

    private val _peerTyping = MutableStateFlow(PeerTypingState.Idle)
    val peerTyping: StateFlow<PeerTypingState> = _peerTyping.asStateFlow()

    /**
     * The message the user is currently composing a reply to (long-press →
     * 回复), or `null`. Lives in the VM so the composer's quote strip survives
     * rotation / the fragment's view being recreated. Cleared on a successful
     * dispatch in [sendText] or explicitly via [clearReplyTarget].
     */
    private val _replyTarget = MutableStateFlow<ChatMessageEntity?>(null)
    val replyTarget: StateFlow<ChatMessageEntity?> = _replyTarget.asStateFlow()

    init {
        Timber.tag(TAG_PERF).d(
            "init: selfUid=%d toUid=%s room=%s pageSize=%d",
            selfUid, toUid?.toString() ?: "global", room, pageSize,
        )
        startLiveSync()
        startPeerTypingObserver()
        viewModelScope.launch { initialLoad() }
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun refresh() {
        Timber.tag(TAG_PERF).d("refresh: windowSize %d → %d", windowSize.value, pageSize)
        pagingJob?.cancel()
        _pagingState.value = PagingState.Idle
        viewModelScope.launch {
            _pageState.value = PageState.Loading(LoadReason.Swipe)
            windowSize.value = pageSize
            nextPageToken = null
            firstPageLoaded = false
            fetchNewest()
        }
    }

    fun retry() {
        Timber.tag(TAG_PERF).d("retry")
        viewModelScope.launch {
            _pageState.value = PageState.Loading(LoadReason.Retry)
            fetchNewest()
        }
    }

    fun loadOlder() {
        if (_pagingState.value != PagingState.Idle) {
            Timber.tag(TAG_PERF).d("loadOlder: skip (pagingState=%s)", _pagingState.value)
            return
        }
        if (nextPageToken == null && messages.value.isNotEmpty()) {
            if (!firstPageLoaded) {
                Timber.tag(TAG_PERF).d("loadOlder: skip (firstPage not loaded yet)")
                return
            }
            Timber.tag(TAG_PERF).d("loadOlder: EndReached (no nextPageToken)")
            _pagingState.value = PagingState.EndReached
            return
        }
        pagingJob = viewModelScope.launch {
            _pagingState.value = PagingState.LoadingMore
            val t0 = System.nanoTime()
            Timber.tag(TAG_PERF).d(
                "loadOlder: start room=%s, pageToken=%s, pageSize=%d, windowSize=%d",
                room, nextPageToken, pageSize, windowSize.value,
            )
            when (val result = historySource.loadPage(room, nextPageToken, pageSize)) {
                is AppResult.Success -> {
                    val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                    val page = result.data
                    Timber.tag(TAG_PERF).d(
                        "loadOlder: API %.1f ms, received=%d, nextToken=%s",
                        apiMs, page.messages.size, page.nextPageToken,
                    )
                    if (page.messages.isNotEmpty()) {
                        store.upsert(page.messages)
                        val oldWindow = windowSize.value
                        windowSize.value += page.messages.size
                        Timber.tag(TAG_PERF).d(
                            "loadOlder: windowSize %d → %d", oldWindow, windowSize.value,
                        )
                    }
                    nextPageToken = page.nextPageToken
                    _pagingState.value = if (nextPageToken == null) {
                        PagingState.EndReached
                    } else {
                        PagingState.Idle
                    }
                }
                is AppResult.Error -> {
                    Timber.tag(TAG_PERF).w("loadOlder: failed: %s", result.error)
                    _pagingState.value = PagingState.Error(result.error)
                }
            }
        }
    }

    fun retryPaging() {
        Timber.tag(TAG_PERF).d("retryPaging")
        _pagingState.value = PagingState.Idle
        loadOlder()
    }

    /**
     * Send a `msg` frame. Returns `true` if the frame was accepted into the
     * WS outgoing buffer (not an end-to-end ACK — broadcast echo is the
     * actual ACK; see doc §3.2 / §4).
     *
     * Lifecycle:
     *  1. Generate UUIDv4 `client_msg_id`
     *  2. Optimistic UPSERT row `state=Sending` so UI shows the message
     *     instantly with a "sending…" indicator
     *  3. Dispatch via [sender]; on rejection mark row `state=Failed`
     *  4. WS echo arrives later (via [stream]) → UPSERT same localKey
     *     `state=Delivered` (the echo path overwrites this row)
     */
    suspend fun sendText(text: String, illustId: Long? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_TEXT_LENGTH) {
            Timber.tag(TAG).w("send rejected: text.length=%d > %d", trimmed.length, MAX_TEXT_LENGTH)
            return false
        }

        // Snapshot the reply target at dispatch time so a clear/re-pick while
        // the frame is in flight can't change what this message quotes.
        val quoted = _replyTarget.value?.takeIf { canReplyTo(it) }
        val replyRef = quoted?.let { ChatReplyRef(uid = it.uid, clientMsgId = it.clientMsgId!!) }

        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val optimistic = ChatMessageEntity(
            localKey = clientMsgId,
            serverId = null,
            clientMsgId = clientMsgId,
            uid = selfUid,
            room = room,
            displayName = null,           // server fills on echo
            text = trimmed,
            illustId = illustId,
            ts = now,
            state = SendState.Sending,
            // Optimistic quote from the local copy of the target; the echo
            // overwrites it with the server's canonical snapshot.
            replyToUid = quoted?.uid,
            replyToCmid = quoted?.clientMsgId,
            replyToDisplayName = quoted?.displayName,
            replyToText = quoted?.text?.take(REPLY_PREVIEW_MAX),
        )

        // Optimistic UI first. windowSize++ so the new row doesn't push
        // an old one out of the LIMIT result set.
        val oldWindow = windowSize.value
        windowSize.value++
        store.upsert(listOf(optimistic))
        Timber.tag(TAG).i(
            "⇡ sendText optimistic cmid=%s room=%s to=%s len=%d windowSize %d→%d",
            clientMsgId, room, toUid?.toString() ?: "global",
            trimmed.length, oldWindow, windowSize.value,
        )

        // If we were in Empty state, flip to Content.
        if (_pageState.value is PageState.Empty) {
            _pageState.value = PageState.Content(Unit)
        }

        // Record send-start so [startLiveSync] can compute round-trip
        // latency when the echo arrives.
        inFlightSends[clientMsgId] = System.nanoTime()

        val accepted = sender.send(
            toUid = toUid, clientMsgId = clientMsgId, text = trimmed,
            illustId = illustId, replyTo = replyRef,
        )
        if (!accepted) {
            inFlightSends.remove(clientMsgId)
            store.upsert(listOf(optimistic.copy(state = SendState.Failed)))
            Timber.tag(TAG).w(
                "⇡ sendText WS REJECTED cmid=%s → state=Failed (no active session?)",
                clientMsgId,
            )
        } else if (quoted != null) {
            // The reply went out — the composer strip has done its job.
            _replyTarget.compareAndSet(quoted, null)
        }
        return accepted
    }

    /**
     * Start composing a reply to [msg]. No-op (and logged) for rows that
     * can't be quoted — see [canReplyTo]; the UI hides the action for those,
     * this is the belt to its braces.
     */
    fun setReplyTarget(msg: ChatMessageEntity) {
        if (!canReplyTo(msg)) {
            Timber.tag(TAG).w("setReplyTarget: %s not replyable (state=%s cmid=%s)",
                msg.localKey, msg.state, msg.clientMsgId ?: "-")
            return
        }
        _replyTarget.value = msg
    }

    fun clearReplyTarget() {
        _replyTarget.value = null
    }

    /**
     * Mark a Sending row Failed in response to a server `err` frame.
     *
     * Per doc §3.2 / §12: when the offending inbound frame carried a
     * `client_msg_id`, server echoes it on the err, and we anchor on that
     * exact cmid — no ambiguity, even with multiple in-flight sends.
     *
     * For frame-level errors that happen before per-msg parsing
     * (`bad_json`, `bad_envelope`, `frame_too_large` of an unparseable
     * envelope, …), [cmid] is `null`. Fall back to "mark the most recent
     * still-Sending row Failed" — the user's last action is overwhelmingly
     * the source of frame-level errors at this layer.
     */
    fun markFailedByClientMsgId(cmid: String?) {
        viewModelScope.launch {
            val target: String? = if (cmid != null) {
                cmid // exact localKey match (self-sent rows store cmid as localKey)
            } else {
                // Fallback: most recent Sending row in the visible window.
                messages.value.firstOrNull { it.state == SendState.Sending }?.localKey
            }
            if (target == null) {
                Timber.tag(TAG).w("markFailed: no in-flight Sending row to anchor (cmid=%s)", cmid ?: "-")
                return@launch
            }
            // Stop any pending echo correlation for this cmid — we now know
            // the server will never broadcast it.
            inFlightSends.remove(target)

            val row = messages.value.firstOrNull { it.localKey == target }
            if (row == null) {
                Timber.tag(TAG).w("markFailed: cmid=%s not in current window", target)
                return@launch
            }
            store.upsert(listOf(row.copy(state = SendState.Failed)))
            Timber.tag(TAG).i(
                "✗ markFailed cmid=%s (exact=%b) → state=Failed",
                target, cmid != null,
            )
        }
    }

    /**
     * Drop an optimistic self-sent row entirely (vs [markFailedByClientMsgId]
     * which keeps it as Failed). For non-retryable policy rejections like
     * `global_send_disabled` (admin closed the public room): retry is pointless
     * and the message was never accepted, so leaving a row — even a Failed one —
     * is just noise. Anchors on cmid exactly; falls back to the most recent
     * Sending row when the server couldn't echo a cmid.
     *
     * Safe to delete by key unconditionally (no "is it still Sending?" guard):
     * the server returns the err XOR broadcasts the echo for a given cmid — never
     * both (handleMsg returns right after sendErr) — and cmid is a fresh UUID per
     * send, so `localKey == cmid` can only be this optimistic row, never a
     * confirmed message. Deleting by key (vs scanning [messages]) also reaches
     * rows that have scrolled out of the current window. Don't add a state guard.
     */
    fun removeByClientMsgId(cmid: String?) {
        viewModelScope.launch {
            val target: String = cmid
                ?: messages.value.firstOrNull { it.state == SendState.Sending }?.localKey
                ?: run {
                    Timber.tag(TAG).w("removeOptimistic: no row to anchor (cmid=%s)", cmid ?: "-")
                    return@launch
                }
            inFlightSends.remove(target)
            store.deleteByLocalKey(target)
            // Mirror the windowSize++ from the optimistic insert so paging stays
            // consistent; never shrink below the base page size.
            if (windowSize.value > pageSize) windowSize.value--
            Timber.tag(TAG).i("✗ removeOptimistic cmid=%s (non-retryable reject)", target)
        }
    }

    /**
     * Signal "user is typing right now" to the peer. Idempotent under
     * burst calls: only the first call within [TYPING_REFRESH_INTERVAL_MS]
     * actually dispatches a `typing` frame. The fragment is expected to
     * invoke this on every keystroke (cheap), letting the VM handle
     * debounce centrally instead of duplicating it at every call site.
     *
     * Global-room VMs (`toUid == null`) are no-ops — server rejects typing
     * for global with `typing_forbidden_for_global`.
     */
    fun notifyTyping() {
        val peer = toUid ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingStartSentMs < TYPING_REFRESH_INTERVAL_MS) return
        // state=null lets server's default ("start") apply — saves a few
        // wire bytes per typing frame at sustained 1/4s. Server treats
        // omitted state as start (see chat/ws.js handleTyping).
        //
        // Only advance the debounce clock when the frame actually made it
        // into the WS buffer. Otherwise a transient disconnect would
        // "consume" the next 4s of typing locally — peer wouldn't see
        // "正在输入..." until 4s after reconnect, even though the user
        // never stopped. Gating on `accepted` re-tries on each keystroke
        // until the wire opens up again.
        val accepted = typingSender.send(peer, null)
        if (accepted) lastTypingStartSentMs = now
    }

    /**
     * Explicit "user stopped typing" signal — called when input clears,
     * send fires, fragment pauses, etc. Server forwards `state:"stop"` so
     * peer can clear the "正在输入..." indicator immediately rather than
     * waiting out the 5s timeout.
     *
     * No-op when [notifyTyping] was never called (lastTypingStartSentMs==0)
     * — saves a wire frame when the user clears an already-empty input.
     */
    fun notifyTypingStop() {
        val peer = toUid ?: return
        if (lastTypingStartSentMs == 0L) return
        lastTypingStartSentMs = 0L
        typingSender.send(peer, "stop")
    }

    fun trimWindow() {
        if (windowSize.value > pageSize) {
            Timber.tag(TAG_PERF).d("trimWindow: %d → %d", windowSize.value, pageSize)
            windowSize.value = pageSize
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun initialLoad() {
        val cachedCount = try {
            store.countByRoom(room)
        } catch (t: Throwable) {
            Timber.tag(TAG_PERF).e(t, "initialLoad: countByRoom threw")
            0
        }
        Timber.tag(TAG_PERF).d("initialLoad: room=%s cached=%d", room, cachedCount)

        if (cachedCount > 0) {
            _pageState.value = PageState.Content(Unit)
            backgroundRefresh()
        } else {
            _pageState.value = PageState.Loading(LoadReason.Initial)
            fetchNewest()
        }
    }

    private suspend fun fetchNewest() {
        val t0 = System.nanoTime()
        when (val result = historySource.loadPage(room, null, pageSize)) {
            is AppResult.Success -> {
                val page = result.data
                if (page.messages.isNotEmpty()) {
                    store.upsert(page.messages)
                }
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
                _pageState.value = if (page.messages.isEmpty() && messages.value.isEmpty()) {
                    PageState.Empty()
                } else {
                    PageState.Content(Unit)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).d("fetchNewest: %d msgs in %.1f ms", page.messages.size, ms)
            }
            is AppResult.Error -> {
                if (messages.value.isNotEmpty()) {
                    _pageState.value = PageState.Content(Unit)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed but cache non-empty: %s", result.error)
                } else {
                    _pageState.value = PageState.Error(result.error)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed: %s", result.error)
                }
            }
        }
    }

    private suspend fun backgroundRefresh() {
        when (val result = historySource.loadPage(room, null, pageSize)) {
            is AppResult.Success -> {
                val page = result.data
                if (page.messages.isNotEmpty()) store.upsert(page.messages)
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
            }
            is AppResult.Error -> {
                Timber.tag(TAG_PERF).w("backgroundRefresh: failed: %s", result.error)
            }
        }
    }

    /**
     * VM-side reactive side effects per inbound msg in this room:
     *  - Grow [windowSize] so the new row stays within the LIMIT-bounded
     *    Room query result
     *  - Correlate echoes against [inFlightSends] for end-to-end latency
     *  - Flip Empty → Content on the first message
     *
     * **No `store.upsert` here.** Persistence is now owned exclusively by
     * `ShaftChatGateway.startAlwaysOnPersister` so messages received while
     * the fragment is closed also land in Room. Doing the UPSERT here too
     * would be redundant (same `localKey`, same content → no-op write).
     */
    private fun startLiveSync() {
        syncJob = viewModelScope.launch {
            stream.observe(room).collect { msg ->
                val oldWindow = windowSize.value
                windowSize.value++

                val cmid = msg.clientMsgId
                val sendStartNs = if (cmid != null) inFlightSends.remove(cmid) else null
                if (sendStartNs != null) {
                    val roundTripMs = (System.nanoTime() - sendStartNs) / 1_000_000.0
                    Timber.tag(TAG).i(
                        "✓ echo received cmid=%s rtt=%.1fms (Sending→Delivered) room=%s",
                        cmid, roundTripMs, room,
                    )
                } else {
                    Timber.tag(TAG_PERF).d(
                        "liveSync: cmid=%s uid=%d windowSize %d→%d",
                        cmid ?: "-", msg.uid, oldWindow, windowSize.value,
                    )
                }

                if (_pageState.value is PageState.Empty) {
                    _pageState.value = PageState.Content(Unit)
                }
            }
        }
    }

    /**
     * Watch peer's `typing` frames filtered to this room. `collectLatest`
     * is the workhorse: each new frame cancels the prior body, so the
     * 5-second auto-clear timer resets on every fresh `start` — exactly
     * the "while typing, keep showing the indicator; 5s after the last
     * frame, clear it" semantics standard IM uses (WhatsApp/Slack).
     *
     * Skipped for global-room VMs — typing isn't a thing there.
     */
    private fun startPeerTypingObserver() {
        if (toUid == null) return
        viewModelScope.launch {
            typingFrames
                .filter { it.room == room && it.uid != selfUid }
                .collectLatest { frame ->
                    if (frame.state == "stop") {
                        _peerTyping.value = PeerTypingState.Idle
                    } else {
                        _peerTyping.value = PeerTypingState(
                            isTyping = true,
                            displayName = frame.displayName,
                        )
                        // Auto-clear if no fresh `start` arrives in 5s.
                        // Cancelled (via collectLatest) on the next frame —
                        // peer's continued typing keeps resetting this delay.
                        delay(PEER_TYPING_TIMEOUT_MS)
                        _peerTyping.value = PeerTypingState.Idle
                    }
                }
        }
    }

    override fun onCleared() {
        Timber.tag(TAG_PERF).d("onCleared: room=%s windowSize=%d", room, windowSize.value)
        // Best-effort: tell peer we stopped typing on fragment teardown so
        // their indicator clears immediately rather than waiting 5s. Won't
        // throw on dead WS — gateway returns false silently.
        if (toUid != null && lastTypingStartSentMs != 0L) {
            typingSender.send(toUid, "stop")
        }
        syncJob?.cancel()
        pagingJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Chat-VM"
        private const val TAG_PERF = "Chat-Perf"
        /** doc §3.1 / §12. */
        const val MAX_TEXT_LENGTH = 2048

        /** Server truncates the quoted excerpt to this many UTF-16 units; mirror it optimistically. */
        private const val REPLY_PREVIEW_MAX = 200

        /**
         * A message can be quoted only once the server knows it: it needs a
         * `client_msg_id` (legacy `server:<id>` rows have none) and must be
         * [SendState.Delivered] — a Sending/Failed optimistic row may never
         * reach the server, and the server rejects quotes of unknown messages
         * with `reply_target_not_found`.
         */
        fun canReplyTo(msg: ChatMessageEntity): Boolean =
            msg.clientMsgId != null && msg.state == SendState.Delivered

        /**
         * How often we re-send a `typing:start` frame while user keeps
         * typing. Server bucket is 10 frames / 10s; 4 s keeps us at 1/4 s
         * sustained — comfortably under the cap and still well within the
         * peer-side 5 s auto-clear timeout (so peer never sees a flicker).
         */
        private const val TYPING_REFRESH_INTERVAL_MS = 4_000L

        /**
         * How long to keep showing "X 正在输入..." after the last `start`
         * frame arrives. Industry convention is ~5 s; we sit at exactly 5 s
         * so the [TYPING_REFRESH_INTERVAL_MS] cadence (4 s) keeps the
         * indicator continuously lit while the peer is actively typing.
         */
        private const val PEER_TYPING_TIMEOUT_MS = 5_000L
    }
}

/**
 * Wire-side typing-frame seam. Production is `ShaftChatGateway::sendTyping`;
 * tests pass a recording lambda. Separate from [WsMsgSender] so each stays
 * SAM-convertible.
 *
 * [state] is `"start"` (or null → server default), or `"stop"`. The VM
 * only ever passes `null` (start) or `"stop"` — caller-side validation
 * happens before dispatch.
 */
fun interface WsTypingSender {
    fun send(toUid: Long, state: String?): Boolean
}

/**
 * Peer-side typing indicator state, surfaced as a StateFlow on
 * [ChatListViewModel.peerTyping]. UI just binds `isTyping` to visibility
 * and renders `displayName` as the prefix when present.
 */
data class PeerTypingState(
    val isTyping: Boolean,
    val displayName: String? = null,
) {
    companion object {
        val Idle = PeerTypingState(isTyping = false, displayName = null)
    }
}

/**
 * Wire-side send seam. Production is `ShaftChatGateway::send`; tests pass
 * a lambda that records calls.
 */
fun interface WsMsgSender {
    fun send(
        toUid: Long?,
        clientMsgId: String,
        text: String,
        illustId: Long?,
        replyTo: ChatReplyRef?,
    ): Boolean
}
