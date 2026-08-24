package ceui.pixiv.chat.api

import ceui.pixiv.chat.ui.ChatRoomEntry
import ceui.pixiv.shaftapi.ShaftHmac
import timber.log.Timber

/**
 * Network-backed source for the chat conversation list. Wraps the two
 * `/api/v1/chat/conversations` endpoints with HMAC signing, error mapping,
 * and DTO → UI-model projection.
 *
 * Pagination is cursor-based per server doc:
 *  - First page: [load] with `cursor = null`. Response pins `global` first
 *    plus the first DM batch.
 *  - Next pages: pass the previous response's `nextCursor` back in. Server
 *    returns DMs only (global doesn't repeat).
 *  - `nextCursor == null` ⇔ end of list.
 *
 * The repository itself is **stateless**. Cursor management lives in the
 * caller (a ViewModel or fragment-scoped state) so re-entries don't share
 * stale cursors across fragment recreations.
 */
class ChatConversationsRepository(
    private val api: ShaftChatApi = ShaftChatHttpClient.api,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Page(
        val items: List<ChatRoomEntry>,
        val nextCursor: String?,
    )

    /** Fetch one page. Throws on transport / 4xx / 5xx — caller renders error. */
    suspend fun load(uid: Long, cursor: String?, limit: Int = 50): Page {
        require(uid > 0L) { "ChatConversationsRepository.load: uid must be > 0, got $uid" }
        val ts = clock().toString()
        val sig = ShaftHmac.signClientIdTs(uid.toString(), ts)
        Timber.tag(TAG).i("→ GET /chat/conversations uid=%d cursor=%s limit=%d", uid, cursor ?: "(head)", limit)
        val t0 = System.nanoTime()
        val resp = api.listConversations(sig = sig, uid = uid, ts = ts, limit = limit, cursor = cursor)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).i(
            "← /chat/conversations %d items in %.1fms next=%s",
            resp.items.size, ms, resp.next_cursor ?: "(end)",
        )
        return Page(
            items = resp.items.map { it.toEntry() },
            nextCursor = resp.next_cursor,
        )
    }

    /**
     * Mark a DM room read up to [lastReadMessageId]. Silently no-ops for
     * `global` because the server refuses that path; the call site is
     * already room-aware so this guard is belt-and-suspenders.
     */
    suspend fun markRead(uid: Long, roomId: String, lastReadMessageId: Long) {
        if (roomId == ChatThreadId.ROOM_GLOBAL) {
            Timber.tag(TAG).d("markRead: skipped for global room")
            return
        }
        require(uid > 0L) { "markRead: uid must be > 0, got $uid" }
        require(lastReadMessageId >= 0L) { "markRead: lastReadMessageId must be >= 0" }
        val ts = clock().toString()
        val sig = ShaftHmac.signClientIdTs(uid.toString(), ts)
        Timber.tag(TAG).i("→ POST /chat/conversations/%s/read lastRead=%d", roomId, lastReadMessageId)
        api.markRead(
            room = roomId,
            sig = sig,
            body = MarkReadRequest(uid = uid, ts = ts, last_read_message_id = lastReadMessageId),
        )
    }

    private fun ConversationItem.toEntry(): ChatRoomEntry {
        // Display name source priority for DM rows:
        //   1. server-provided peer_display_name (already includes "匿名_<uid>" fallback)
        //   2. last message's display_name *if it's the peer's message*
        //   3. derived from peer_uid: "匿名_<peer_uid>"
        //   4. last-resort: room_id raw string
        val kind = when (kind) {
            "global" -> ChatRoomEntry.Kind.GLOBAL
            "dm" -> ChatRoomEntry.Kind.ONE_ON_ONE
            else -> ChatRoomEntry.Kind.ONE_ON_ONE  // future "group" displays as 1v1 placeholder
        }
        val title = when (kind) {
            ChatRoomEntry.Kind.GLOBAL -> CONVENTION_GLOBAL_TITLE  // resolved to a string res by the adapter/fragment
            ChatRoomEntry.Kind.ONE_ON_ONE -> peer_display_name
                ?: peer_uid?.let { "匿名_$it" }
                ?: room_id
        }
        return ChatRoomEntry(
            room = room_id,
            kind = kind,
            title = title,
            previewText = last_message?.text.orEmpty(),
            previewSenderUid = last_message?.uid,
            previewSenderDisplayName = last_message?.display_name,
            lastMessageId = last_message?.id,
            lastTs = last_message?.ts ?: 0L,
            peerUid = peer_uid,
            unreadCount = unread_count ?: 0,
        )
    }

    companion object {
        private const val TAG = "Chat-ConvRepo"
        /** Sentinel that the fragment swaps for the localized "公屏闲聊" string. */
        const val CONVENTION_GLOBAL_TITLE: String = "__global__"
    }
}
