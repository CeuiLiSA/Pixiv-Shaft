package ceui.pixiv.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for chat messages. Schema follows doc §9.2 — keyed by a
 * client-derived `localKey` so optimistic-send + WS-echo can collapse to
 * one row via UPSERT.
 *
 * ## Primary key — `localKey`
 *
 * `localKey = clientMsgId ?? "server:$serverId"`.
 *
 *  - For messages **the user sent**: optimistic write uses the freshly
 *    generated `clientMsgId` as localKey + `state = Sending`. When the
 *    server's broadcast echo arrives (same `clientMsgId`), UPSERT
 *    overwrites the row with `state = Delivered`. No duplicate row.
 *  - For messages **other users sent**: WS broadcast carries the sender's
 *    `clientMsgId`; UPSERT keyed on it makes duplicate broadcasts a no-op
 *    (broker.publish fires before INSERT-OR-IGNORE — duplicate frames are
 *    possible across retries; see doc §4.2).
 *  - For messages from `/chat/history` that **predate** the client_msg_id
 *    column on the server (legacy direct inserts have null), localKey
 *    falls back to `"server:$serverId"`. These rows have a stable
 *    server-issued id, so the synthetic key still dedupes across pages.
 *
 * ## `room`
 *
 * String per doc §3.2:
 *  - `"global"` for public broadcasts
 *  - decimal uint64 string (≤ 20 chars) from
 *    [ceui.pixiv.chat.api.ChatThreadId.oneOnOneThreadId] for 1v1
 *
 * ## Index
 *
 * `(room, ts)` covers the dominant query "newest N messages in room R,
 * ordered by ts DESC". Single B-tree scan; no extra sort.
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["room", "ts"])],
)
data class ChatMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_key")
    val localKey: String,

    /** Server autoincrement id; only present on rows that came from `/chat/history`. */
    @ColumnInfo(name = "server_id")
    val serverId: Long? = null,

    @ColumnInfo(name = "client_msg_id")
    val clientMsgId: String? = null,

    /** Sender pixiv uid. */
    val uid: Long,

    /** `"global"` or 1v1 thread id (decimal uint64 string). */
    val room: String,

    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    val text: String? = null,

    @ColumnInfo(name = "illust_id")
    val illustId: Long? = null,

    /** Server-stamped broadcast ts (ms since epoch). */
    val ts: Long,

    /** Local lifecycle state — see [SendState]. */
    val state: SendState = SendState.Delivered,

    // ── Reply-to (quoted message) ─────────────────────────────────────
    // A message can quote one other message in the same room. The reference
    // is the quoted message's `(uid, client_msg_id)` — the only identity
    // every message stably has on both ends (WS frames carry no server id),
    // and equal to the quoted row's own `localKey`, so "tap quote → jump to
    // original" is a plain localKey lookup in the current list.
    //
    // `replyToDisplayName` / `replyToText` are the server's snapshot of the
    // quoted message (author's current name + ≤200-char excerpt), delivered
    // on both the WS frame and /history rows, so the bubble renders the
    // quote even when the original isn't in the local store. `replyToText`
    // null while `replyToCmid` is set ⇒ original was purged server-side
    // ("原消息已不可用").

    @ColumnInfo(name = "reply_to_uid")
    val replyToUid: Long? = null,

    @ColumnInfo(name = "reply_to_cmid")
    val replyToCmid: String? = null,

    @ColumnInfo(name = "reply_to_display_name")
    val replyToDisplayName: String? = null,

    @ColumnInfo(name = "reply_to_text")
    val replyToText: String? = null,
) {
    /** True when this message quotes another one (whether or not the original still exists). */
    val isReply: Boolean get() = replyToCmid != null

    companion object {
        /** Synthesize a localKey from a server id when the row has no client_msg_id. */
        fun localKeyForServer(serverId: Long): String = "server:$serverId"
    }
}

/**
 * Per-message send lifecycle. Only meaningful for self-sent rows; rows from
 * others' broadcasts and from /history backfill are always [Delivered].
 */
enum class SendState {
    /** Local optimistic insert; WS broadcast echo not received yet. */
    Sending,
    /** Server confirmed (echo received) or originated server-side. */
    Delivered,
    /** Send failed (WS send rejected; client must retry). */
    Failed,
}
