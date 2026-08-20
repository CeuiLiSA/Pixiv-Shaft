package ceui.pixiv.chat.api

import com.google.gson.JsonParser
import timber.log.Timber

/**
 * Server → client WS frames per `docs/ws-chat-integration.md` §3.2.
 *
 * Four real kinds (`hello`/`msg`/`err`/`pong`) plus a fallback [Unknown]
 * for "got valid envelope but unrecognised kind" so the decoder never
 * silently drops or crashes the stream.
 *
 * **Identity model**: uid (`Long` pixiv user id) is the only identity.
 * The legacy 64-hex `client_id` is gone (`switch from room-subscribe model
 * to uid-routing`).
 */
/**
 * Reference to (and, inbound, a snapshot of) a quoted message — the
 * `reply_to` object on `msg` frames and `/history` rows.
 *
 * Identity is `(uid, clientMsgId)`: the only handle every message stably
 * has on both ends (WS frames carry no server id), and equal to the quoted
 * row's local `localKey`.
 *
 * Outbound (client → server) only [uid] + [clientMsgId] are sent; the server
 * validates the target exists **in the same room** and fills
 * [displayName] (author's current name) + [text] (≤200-char excerpt) on the
 * broadcast and on history rows. [text] `null` on an inbound ref ⇒ the
 * original was purged server-side — render as "原消息已不可用".
 */
data class ChatReplyRef(
    val uid: Long,
    val clientMsgId: String,
    val displayName: String? = null,
    val text: String? = null,
)

sealed interface ChatFrame {

    /**
     * `{ "kind": "hello", "uid", "display_name", "server_ts", "global_send_enabled"? }`
     * — first frame after handshake. [globalSendEnabled] is the current public-room
     * send switch; `null` only when talking to an older server that omits it (treat
     * as enabled). Live changes arrive via [GlobalSendState].
     */
    data class Hello(
        val uid: Long,
        val displayName: String?,
        val serverTs: Long,
        val globalSendEnabled: Boolean? = null,
    ) : ChatFrame

    /**
     * `{ "kind": "msg", "room", "uid", "display_name", "client_msg_id",
     *    "text", "illust_id"?, "ts", "reply_to"? }`
     *
     * `reply_to` (optional) = [ChatReplyRef] snapshot of the quoted message
     * when this message is a reply.
     *
     * Server populates `room` based on routing:
     *  - `"global"` for public broadcasts
     *  - `pairRoomId(uid_a, uid_b)` decimal string for 1v1
     *
     * `client_msg_id` is the dedup anchor — see doc §4. UPSERT into the
     * local store keyed by this id so duplicate broadcasts (broker may
     * deliver the same msg twice between deliverToUid + DB INSERT OR IGNORE)
     * collapse to one row.
     */
    data class Msg(
        val room: String,
        val uid: Long,
        val displayName: String?,
        val clientMsgId: String?,
        val text: String?,
        val illustId: Long?,
        val ts: Long,
        val replyTo: ChatReplyRef? = null,
    ) : ChatFrame

    /**
     * `{ "kind": "err", "code", "client_msg_id"?, "message"? }` — protocol /
     * rate-limit / validation / policy error. Connection stays open.
     *
     * When the offending inbound frame carried a `client_msg_id`, server
     * echoes it on the err so the client can anchor the failure to that
     * exact optimistic row (per doc §3.2 / §12). Frame-level errors that
     * happen BEFORE per-msg parsing (`bad_json`, `bad_envelope`,
     * `frame_too_large` of unparseable bytes, …) leave [clientMsgId]
     * `null` — callers fall back to "most recent Sending" heuristic.
     *
     * [message] is an optional server-supplied, user-displayable string (e.g.
     * `global_send_disabled` carries "公共聊天室当前已关闭发言"). When present,
     * prefer it for the toast over a client-side code→text map — it lets the
     * server reword without a client release. Most codes omit it; the UI keeps
     * its own friendly mapping as the fallback (never show the raw code).
     */
    data class Err(
        val code: String,
        val clientMsgId: String?,
        val message: String? = null,
    ) : ChatFrame

    /** `{ "kind": "pong", "server_ts" }` — reply to client app-level `ping`. */
    data class Pong(val serverTs: Long) : ChatFrame

    /**
     * `{ "kind": "typing", "room", "uid", "display_name", "state", "ts" }`
     *
     * Server-forwarded "X 正在输入" / "X 停止输入" signal, DM-only (global
     * is rejected server-side with `typing_forbidden_for_global`). Fire-and-
     * forget — no `client_msg_id`, no persistence, peer only (sender does
     * NOT receive an echo of its own typing).
     *
     * [state] is `"start"` (default) or `"stop"`. The 5-second client-side
     * timeout on `"start"` is a *client* convention — server never sends a
     * separate "expired" frame; if no fresh `start` arrives within ~5s the
     * receiver should clear the indicator on its own.
     */
    data class Typing(
        val room: String,
        val uid: Long,
        val displayName: String?,
        val state: String,
        val ts: Long,
    ) : ChatFrame

    /**
     * `{ "kind": "global_send_state", "enabled": Boolean, "server_ts" }` — pushed
     * to all connections when an admin toggles the public-room send switch. Lets
     * a client already sitting in the global room enable/disable its input live
     * (vs only learning at next handshake via [Hello.globalSendEnabled]).
     */
    data class GlobalSendState(val enabled: Boolean) : ChatFrame

    /** Valid JSON but unknown / malformed envelope. Logged, dead-lettered, stream stays alive. */
    data class Unknown(val raw: String) : ChatFrame
}

object ChatFrameDecoder {

    private const val TAG = "Chat-Frame"

    fun decode(raw: String): ChatFrame = try {
        decodeOrThrow(raw)
    } catch (t: Throwable) {
        // Per docs §9.1 "onMessage 解析失败 → IncomingMessage.Unknown(raw)
        // 落到 dead-letter, 不挂掉流". Any decode-time exception (malformed
        // JSON, non-numeric ts, weird type, …) gets logged and downgraded.
        Timber.tag(TAG).w(t, "decode failed, dead-lettered: %s", raw.take(120))
        ChatFrame.Unknown(raw)
    }

    private fun decodeOrThrow(raw: String): ChatFrame {
        val root = JsonParser.parseString(raw)
        if (!root.isJsonObject) return ChatFrame.Unknown(raw)
        val obj = root.asJsonObject
        val kind = obj.get("kind")?.asStringOrNull()?.takeIf { it.isNotEmpty() }
            ?: run {
                Timber.tag(TAG).w("envelope missing 'kind': %s", raw.take(120))
                return ChatFrame.Unknown(raw)
            }
        return when (kind) {
            "hello" -> {
                val uid = obj.get("uid")?.asLongOrNull()
                    ?: run {
                        Timber.tag(TAG).w("hello frame missing uid: %s", raw.take(120))
                        return ChatFrame.Unknown(raw)
                    }
                ChatFrame.Hello(
                    uid = uid,
                    displayName = obj.get("display_name")?.asStringOrNull(),
                    serverTs = obj.get("server_ts")?.asLongOrNull() ?: 0L,
                    globalSendEnabled = obj.get("global_send_enabled")?.asBooleanOrNull(),
                )
            }
            "msg" -> {
                // Required: ts, uid, room. Missing any → can't dedup / route → dead letter.
                val ts = obj.get("ts")?.asLongOrNull()
                val uid = obj.get("uid")?.asLongOrNull()
                val room = obj.get("room")?.asStringOrNull()
                if (ts == null || uid == null || room == null) {
                    Timber.tag(TAG).w("msg frame missing ts/uid/room: %s", raw.take(120))
                    ChatFrame.Unknown(raw)
                } else ChatFrame.Msg(
                    room = room,
                    uid = uid,
                    displayName = obj.get("display_name")?.asStringOrNull(),
                    clientMsgId = obj.get("client_msg_id")?.asStringOrNull(),
                    text = obj.get("text")?.asStringOrNull(),
                    illustId = obj.get("illust_id")?.asLongOrNull(),
                    ts = ts,
                    replyTo = obj.get("reply_to")?.let(::decodeReplyTo),
                )
            }
            "err" -> ChatFrame.Err(
                code = obj.get("code")?.asStringOrNull() ?: "unknown",
                clientMsgId = obj.get("client_msg_id")?.asStringOrNull(),
                message = obj.get("message")?.asStringOrNull(),
            )
            "pong" -> ChatFrame.Pong(serverTs = obj.get("server_ts")?.asLongOrNull() ?: 0L)
            "typing" -> {
                // Required: uid, room. `ts` is informational (client uses
                // its own wall clock for the 5s timeout), `state` defaults
                // to "start" if absent — mirroring server's own default.
                val uid = obj.get("uid")?.asLongOrNull()
                val room = obj.get("room")?.asStringOrNull()
                if (uid == null || room == null) {
                    Timber.tag(TAG).w("typing frame missing uid/room: %s", raw.take(120))
                    ChatFrame.Unknown(raw)
                } else ChatFrame.Typing(
                    room = room,
                    uid = uid,
                    displayName = obj.get("display_name")?.asStringOrNull(),
                    state = obj.get("state")?.asStringOrNull() ?: "start",
                    ts = obj.get("ts")?.asLongOrNull() ?: 0L,
                )
            }
            "global_send_state" -> {
                val enabled = obj.get("enabled")?.asBooleanOrNull()
                if (enabled == null) {
                    Timber.tag(TAG).w("global_send_state missing 'enabled': %s", raw.take(120))
                    ChatFrame.Unknown(raw)
                } else ChatFrame.GlobalSendState(enabled = enabled)
            }
            else -> ChatFrame.Unknown(raw).also {
                Timber.tag(TAG).d("unknown kind=%s — ignored", kind)
            }
        }
    }

    /**
     * `reply_to` object → [ChatReplyRef]. Requires `uid` + `client_msg_id`
     * (without them the quote can't be anchored to anything); a malformed
     * object degrades to `null` — the message still renders, just without its
     * quote — rather than dead-lettering the whole frame.
     */
    private fun decodeReplyTo(el: com.google.gson.JsonElement): ChatReplyRef? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val uid = o.get("uid")?.asLongOrNull() ?: return null
        val cmid = o.get("client_msg_id")?.asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return ChatReplyRef(
            uid = uid,
            clientMsgId = cmid,
            displayName = o.get("display_name")?.asStringOrNull(),
            text = o.get("text")?.asStringOrNull(),
        )
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonPrimitive -> asString
        else -> null
    }

    private fun com.google.gson.JsonElement.asLongOrNull(): Long? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isNumber -> asLong
        isJsonPrimitive && asJsonPrimitive.isString -> asString.toLongOrNull()
        else -> null
    }

    private fun com.google.gson.JsonElement.asBooleanOrNull(): Boolean? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isBoolean -> asBoolean
        isJsonPrimitive && asJsonPrimitive.isString -> asString.toBooleanStrictOrNull()
        isJsonPrimitive && asJsonPrimitive.isNumber -> asInt != 0
        else -> null
    }
}

// ── Client → Server frame builders ─────────────────────────────────────────

object ChatFrameEncoder {

    /**
     * Build a public-room `msg` frame:
     * `{"kind":"msg","room":"global","client_msg_id":<id>,"text":<text>,"illust_id":<id?>,"reply_to":{...}?}`
     *
     * [replyTo] (optional) quotes another message in the room — only its
     * `uid` + `client_msg_id` go on the wire; the server resolves and
     * snapshots the rest (see [ChatReplyRef]).
     */
    fun msgGlobal(
        clientMsgId: String,
        text: String,
        illustId: Long? = null,
        replyTo: ChatReplyRef? = null,
    ): String {
        val esc = escapeJsonString(text)
        val idEsc = escapeJsonString(clientMsgId)
        return buildString {
            append("""{"kind":"msg","room":"global","client_msg_id":"""")
            append(idEsc)
            append("""","text":"""")
            append(esc)
            append('"')
            if (illustId != null) append(""","illust_id":""").append(illustId)
            appendReplyTo(replyTo)
            append('}')
        }
    }

    /**
     * Build a 1v1 `msg` frame:
     * `{"kind":"msg","to_uid":<long>,"client_msg_id":<id>,"text":<text>,"illust_id":<id?>}`
     *
     * Doc §5: never send a numeric `room` from the client. Server derives
     * the room from `(self_uid, to_uid)` via `pairRoomId` (weaver ReverseXOR),
     * and ACL is enforced by handshake-authed `self_uid`. Sending raw
     * numeric `room` is rejected as `err.code = "room_forbidden"`.
     */
    fun msg1v1(
        toUid: Long,
        clientMsgId: String,
        text: String,
        illustId: Long? = null,
        replyTo: ChatReplyRef? = null,
    ): String {
        val esc = escapeJsonString(text)
        val idEsc = escapeJsonString(clientMsgId)
        return buildString {
            append("""{"kind":"msg","to_uid":""")
            append(toUid)
            append(""","client_msg_id":"""")
            append(idEsc)
            append("""","text":"""")
            append(esc)
            append('"')
            if (illustId != null) append(""","illust_id":""").append(illustId)
            appendReplyTo(replyTo)
            append('}')
        }
    }

    /** `,"reply_to":{"uid":<long>,"client_msg_id":"<id>"}` — or nothing when [ref] is null. */
    private fun StringBuilder.appendReplyTo(ref: ChatReplyRef?) {
        if (ref == null) return
        append(""","reply_to":{"uid":""")
        append(ref.uid)
        append(""","client_msg_id":"""")
        append(escapeJsonString(ref.clientMsgId))
        append(""""}""")
    }

    /**
     * Build a 1v1 `typing` frame:
     * `{"kind":"typing","to_uid":<long>,"state":"start"|"stop"}`
     *
     * DM-only — server rejects typing with `room:"global"` as
     * `typing_forbidden_for_global`. Omit [state] to let server default to
     * `"start"`.
     *
     * State is an *enum* (start / stop / omitted), not free text. A strict
     * `require` rejects anything else at dev time rather than letting a
     * mistyped value reach the wire and bounce back as `bad_state`. We do
     * NOT JSON-escape because the only legitimate values contain no JSON
     * metacharacters; escaping would silently mask the same kind of bug.
     */
    fun typing1v1(toUid: Long, state: String? = null): String {
        require(state == null || state == "start" || state == "stop") {
            "typing state must be null / \"start\" / \"stop\", got: $state"
        }
        return buildString {
            append("""{"kind":"typing","to_uid":""")
            append(toUid)
            if (state != null) {
                append(""","state":"""")
                append(state)
                append('"')
            }
            append('}')
        }
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
