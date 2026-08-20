package ceui.pixiv.chat.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * HTTP companion for the shaft-api-v2 chat WebSocket. See
 * `docs/ws-chat-integration.md` §8 for the wire format.
 *
 * Identity field is `uid: Long` (pixiv user id). The legacy 64-hex
 * `client_id` is gone — server switched to uid-routing.
 *
 * The base URL is the same `BuildConfig.SHAFT_EVENTS_BASE_URL` used by the
 * events stack — server hosts events and chat under `/api/v1/` on one port.
 */
interface ShaftChatApi {

    /**
     * Pull history. `before` is the `id` of the **oldest** item in the
     * previous page (= `items[0].id`); omit for the most recent page.
     * Empty `items` means top reached. `limit` caps server-side at 200.
     *
     * @param room `"global"` for public broadcasts, or a decimal uint64
     *   string for a 1v1 thread id (from
     *   [ChatThreadId.oneOnOneThreadId]). ⚠️ 1v1 history has no ACL today
     *   — see doc §10.
     */
    @GET("/api/v1/chat/history")
    suspend fun history(
        @Query("room") room: String = "global",
        @Query("limit") limit: Int = 50,
        @Query("before") before: Long? = null,
    ): ChatHistoryResponse

    /** Public lookup of any uid's current display name (for back-filling history rows with unfamiliar uids). */
    @GET("/api/v1/chat/profile")
    suspend fun getProfile(@Query("uid") uid: Long): ChatProfileResponse

    /**
     * Rename self. `sig` = `HMAC_SHA256(secret_ascii, "${uid}|${ts}")` hex
     * lowercase — same scheme as the WS upgrade URL.
     *
     * `display_name`: 1–32 UTF-16 units, UTF-8 byte length ≤ 96, no ASCII
     * control chars. Server `trim()`s leading/trailing whitespace before
     * validating, so the client can be permissive.
     */
    @POST("/api/v1/chat/profile")
    suspend fun setProfile(
        @Header("X-Shaft-Sign") sig: String,
        @Body body: SetProfileRequest,
    ): SetProfileResponse

    /** Debug/observability — current room online count + total message count. */
    @GET("/api/v1/chat/stats")
    suspend fun stats(@Query("room") room: String = "global"): ChatStatsResponse

    // ──────────────── Conversations (commit e1d58cf, server v4) ────────────────
    // server: GET /api/v1/chat/conversations — return all rooms I'm in
    //         (global pinned first, then DMs by recent activity, cursor-paged).
    // sig:    same HMAC scheme as setProfile / WS handshake:
    //         HMAC_SHA256(secret, "${uid}|${ts}") hex lowercase.
    //
    // 注:`ts` 必须跟 query 里 `ts` 完全同字面 (canonical decimal,
    // 13–14 位)。`String(Date.now())` 之类直接对齐;不要先 Long 签名再
    // 重新格式化,任何 canonicalisation drift = 401 bad_sig。

    /**
     * List conversations for [uid]. Cursor pagination: pass [cursor] = null
     * for the first page (which always pins `global` first); pass the
     * server's previous `next_cursor` for subsequent pages (DM-only).
     * `next_cursor == null` in response means end-of-list.
     */
    @GET("/api/v1/chat/conversations")
    suspend fun listConversations(
        @Header("X-Shaft-Sign") sig: String,
        @Query("uid") uid: Long,
        @Query("ts") ts: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): ConversationListResponse

    /**
     * Mark conversation [room] read up through [lastReadMessageId]. Server
     * recomputes the unread count from `chat_messages` (not from the
     * client's view of unread), so stale clients can't accidentally zero
     * unreads they haven't actually seen.
     *
     * `room` cannot be `"global"` (server returns 400 `read_not_supported_for_global`).
     */
    @POST("/api/v1/chat/conversations/{room}/read")
    suspend fun markRead(
        @retrofit2.http.Path("room") room: String,
        @Header("X-Shaft-Sign") sig: String,
        @Body body: MarkReadRequest,
    ): MarkReadResponse
}

// ── Conversation DTOs (1:1 with server wire format) ─────────────────────────

data class ConversationListResponse(
    val uid: Long,
    val limit: Int,
    val items: List<ConversationItem>,
    val next_cursor: String?,
)

/**
 * One conversation. Field nullability follows server contract:
 *  - `peer_uid` / `peer_display_name`: dm-only; may also be null if
 *    `peerFromRoomId` couldn't reverse-derive the uid (server keeps the
 *    row, returns nulls — UI should fall back to showing the room id).
 *  - `unread_count` / `last_read_message_id`: dm-only, both null for global
 *    (server doesn't authoritatively track global unread).
 *  - `last_message`: null only when the room has literally no messages
 *    yet (fresh global on a clean DB).
 */
data class ConversationItem(
    val room_id: String,
    /** `"global"` | `"dm"` — future `"group"`. */
    val kind: String,
    val peer_uid: Long? = null,
    val peer_display_name: String? = null,
    val last_message: ConversationLastMessage? = null,
    val unread_count: Int? = null,
    val last_read_message_id: Long? = null,
    val muted: Boolean = false,
    val pinned: Boolean = false,
)

/**
 * Last-message snapshot. `text` is **server-truncated to ~100 chars** for
 * list rendering — full text via [history] when the user actually opens
 * the thread.
 */
data class ConversationLastMessage(
    val id: Long,
    val uid: Long?,
    val display_name: String?,
    val text: String?,
    val ts: Long,
)

data class MarkReadRequest(
    val uid: Long,
    val ts: String,
    val last_read_message_id: Long,
)

data class MarkReadResponse(
    val ok: Boolean,
    val room: String,
    val last_read_message_id: Long,
)

// ── DTOs (1:1 with server wire format) ─────────────────────────────────────

data class ChatHistoryResponse(
    val room: String,
    val limit: Int,
    val items: List<ChatHistoryItem>,
)

/**
 * Server-side row from `/chat/history`. `id` is the server autoincrement
 * (only history items have it; WS broadcasts of new messages don't —
 * see doc §3.2 Msg). `client_msg_id` is the dedup anchor; server-direct
 * inserts predating the column may carry `null`, in which case fall back
 * to `"server:$id"` as the local key.
 */
data class ChatHistoryItem(
    val id: Long,
    val uid: Long,
    val client_msg_id: String?,
    val display_name: String?,
    val text: String?,
    val illust_id: Long?,
    val ts: Long,
    /** Present only when this message quotes another one; see [ChatHistoryReplyTo]. */
    val reply_to: ChatHistoryReplyTo? = null,
)

/**
 * `reply_to` snapshot on a `/chat/history` row — same shape as the WS frame's
 * `reply_to` (see [ChatReplyRef]). `text == null` ⇒ the quoted original has
 * been purged server-side; `display_name` is still derived from `uid`.
 */
data class ChatHistoryReplyTo(
    val uid: Long,
    val client_msg_id: String,
    val display_name: String?,
    val text: String?,
)

data class ChatProfileResponse(
    val uid: Long,
    val display_name: String?,
)

data class SetProfileRequest(
    val uid: Long,
    val ts: Long,
    val display_name: String,
)

data class SetProfileResponse(
    val ok: Boolean,
    val display_name: String?,
)

data class ChatStatsResponse(
    val room: String,
    val online: Int,
    val total_connections: Int? = null,
    val total_messages: Long,
)
