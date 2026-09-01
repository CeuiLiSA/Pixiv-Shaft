package ceui.pixiv.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.api.Client
import ceui.pixiv.chat.api.ChatConversationsRepository
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.ui.ChatRoomEntry
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Conversation-list state holder. Single source of truth for what the
 * `ChatRoomListFragment` renders:
 *
 *   - `items`            — the rows visible right now (Global pinned first,
 *                          DMs sorted by recent activity)
 *   - `nextCursor`       — server pagination cursor; `null` once exhausted
 *   - `loadingMore`      — guards against re-entrant pagination
 *   - `hasUnknownRoom..` — flag set when a WS msg lands in a room we don't
 *                          have locally yet; triggers a fresh /conversations
 *                          pull on the next refresh
 *
 * Side effects (network / WS bumps) live here so the fragment can shrink
 * to "observe + dispatch user events". viewModelScope cancels everything
 * when the user navigates away.
 *
 * Avatar resolution is cached per peer uid:
 *   - absent from map        → never fetched
 *   - present, value=null    → fetched, server had no avatar (or call
 *                              failed) — don't hammer until next refresh
 *   - present, value=url     → bind via Glide on the row
 *
 * The class is intentionally **stateless about the WS subscription** —
 * the fragment owns the lifecycle-bound collect on `ShaftChatGateway.incoming`
 * and pushes decoded frames in via [onWsMsg]. That keeps the VM unit-testable
 * with no need to stand up a fake WS gateway.
 */
class ChatRoomListViewModel(
    private val repo: ChatConversationsRepository = ChatConversationsRepository(),
) : ViewModel() {

    data class UiState(
        val items: List<ChatRoomEntry> = emptyList(),
        val nextCursor: String? = null,
        val loadingMore: Boolean = false,
        val hasUnknownRoomSinceLastRefresh: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val avatarCache = mutableMapOf<Long, String?>()
    private val pendingAvatarFetches = mutableSetOf<Long>()

    /** Pull page 1 — authoritative refresh of the list. Cancels any in-flight load. */
    fun refresh() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            Timber.tag(TAG).w("refresh: not logged in (uid=%d) — skip", uid)
            _state.update { UiState() }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val page = repo.load(uid = uid, cursor = null, limit = 50)
                val items = page.items.map(::applyCachedAvatar)
                _state.update {
                    UiState(
                        items = items,
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                        hasUnknownRoomSinceLastRefresh = false,
                    )
                }
                ensureAvatars(items)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "refresh failed")
            }
        }
    }

    /** Append the next page if a cursor is available + we aren't already loading. */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.nextCursor == null) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repo.load(uid = uid, cursor = current.nextCursor, limit = 50)
                val newItems = page.items.map(::applyCachedAvatar)
                _state.update {
                    // Server contract: page 2+ never includes 'global'. Plain
                    // append; the adapter's diffCallback keys on room so any
                    // accidental dup would be collapsed.
                    it.copy(
                        items = it.items + newItems,
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                    )
                }
                ensureAvatars(newItems)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "loadMore failed")
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /**
     * Apply optimistic UI changes when a WS msg lands:
     *  - known room → bump preview / unread / move to top
     *  - unknown room → flag for next refresh (server has the real
     *    last_message.id + peer_display_name we'd otherwise have to invent)
     */
    fun onWsMsg(frame: ChatFrame.Msg) {
        val current = _state.value
        val idx = current.items.indexOfFirst { it.room == frame.room }
        if (idx < 0) {
            _state.update { it.copy(hasUnknownRoomSinceLastRefresh = true) }
            return
        }
        val selfUid = SessionManager.loggedInUid
        val existing = current.items[idx]
        val bumpUnread = existing.kind == ChatRoomEntry.Kind.ONE_ON_ONE &&
            frame.uid != selfUid
        val updated = existing.copy(
            previewText = frame.text.orEmpty(),
            previewSenderUid = frame.uid,
            previewSenderDisplayName = frame.displayName,
            lastTs = frame.ts,
            unreadCount = if (bumpUnread) existing.unreadCount + 1 else existing.unreadCount,
        )
        // Move the bumped row to the top (after Global, which is index 0 if present).
        val rest = current.items.toMutableList().apply { removeAt(idx) }
        val insertAt = if (rest.firstOrNull()?.kind == ChatRoomEntry.Kind.GLOBAL) 1 else 0
        rest.add(insertAt, updated)
        _state.update { it.copy(items = rest) }
    }

    /**
     * The user tapped a row — open the chat is the fragment's job, we just
     * clear the local unread badge so it doesn't linger while /read goes
     * out-of-band from DemoChatListFragment.observeMarkRead.
     */
    fun onRoomTapped(room: String) {
        val idx = _state.value.items.indexOfFirst { it.room == room }
        if (idx < 0) return
        val entry = _state.value.items[idx]
        if (entry.unreadCount == 0) return
        val updated = entry.copy(unreadCount = 0)
        _state.update { s ->
            s.copy(items = s.items.toMutableList().apply { set(idx, updated) })
        }
    }

    // ──────────────── Avatar resolution ────────────────────────────────

    private fun applyCachedAvatar(entry: ChatRoomEntry): ChatRoomEntry {
        val peer = entry.peerUid ?: return entry
        val cached = avatarCache[peer] ?: return entry
        return entry.copy(avatarUrl = cached)
    }

    private fun ensureAvatars(items: List<ChatRoomEntry>) {
        val toFetch = items.mapNotNull { it.peerUid }
            .toSet()
            .filter { it !in avatarCache && it !in pendingAvatarFetches }
        if (toFetch.isEmpty()) return
        for (peer in toFetch) {
            pendingAvatarFetches += peer
            viewModelScope.launch {
                val url = runCatching {
                    Client.appApi.getUserProfile(peer).user?.profile_image_urls?.findMaxSizeUrl()
                }.onFailure {
                    Timber.tag(TAG).v(it, "avatar fetch failed for uid=%d", peer)
                }.getOrNull()
                pendingAvatarFetches -= peer
                avatarCache[peer] = url
                if (url == null) return@launch
                _state.update { s ->
                    s.copy(items = s.items.map { e ->
                        if (e.peerUid == peer && e.avatarUrl == null) e.copy(avatarUrl = url) else e
                    })
                }
            }
        }
    }

    companion object {
        private const val TAG = "Chat-RoomListVM"
    }
}
