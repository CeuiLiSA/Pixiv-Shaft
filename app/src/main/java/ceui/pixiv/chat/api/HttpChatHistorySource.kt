package ceui.pixiv.chat.api

import ceui.pixiv.chat.core.AppError
import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.MessagePage
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Production [ChatHistorySource] backed by [ShaftChatApi].
 *
 * `pageToken` is the previous page's **oldest item id** stringified — the
 * [ChatHistorySource] interface uses opaque string tokens but the server's
 * `before` parameter is a `Long`. First page: pass `null`.
 *
 * Field mapping ([ChatHistoryItem] → [ChatMessageEntity]):
 *
 * - `client_msg_id` ?? `"server:$id"` → [ChatMessageEntity.localKey] (UPSERT key)
 * - `id`            → [ChatMessageEntity.serverId]
 * - `uid`           → [ChatMessageEntity.uid] (server's pixiv uid, used directly)
 * - server's `room` (passed through this query) → [ChatMessageEntity.room]
 * - `display_name`  → [ChatMessageEntity.displayName]
 * - `text`, `illust_id`, `ts` → straight passthrough
 * - `reply_to.{uid,client_msg_id,display_name,text}` → `replyTo*` columns (absent → all null)
 * - state           → [SendState.Delivered] (history is always already broadcast)
 */
class HttpChatHistorySource(
    private val api: ShaftChatApi = ShaftChatHttpClient.api,
) : ChatHistorySource<ChatMessageEntity> {

    override suspend fun loadPage(
        room: String,
        pageToken: String?,
        pageSize: Int,
    ): AppResult<MessagePage<ChatMessageEntity>> {
        val before = pageToken?.toLongOrNull()
        val limit = pageSize.coerceIn(1, 200)
        Timber.tag(TAG).i("→ GET /chat/history room=%s limit=%d before=%s", room, limit, before)
        val t0 = System.nanoTime()
        return runCatching {
            api.history(room = room, limit = limit, before = before)
        }.fold(
            onSuccess = { resp ->
                val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                // Server returns ts-ascending (oldest→newest); MessagePage contract is
                // descending. Reverse so downstream code can trust it.
                val entities = resp.items.asReversed().map { toEntity(it, resp.room) }
                val nextCursor = resp.items.firstOrNull()?.id?.toString()
                val oldestTs = resp.items.firstOrNull()?.ts
                val newestTs = resp.items.lastOrNull()?.ts
                Timber.tag(TAG).i(
                    "← /chat/history room=%s %d items in %.1fms ts=[%s..%s] nextCursor=%s",
                    resp.room, entities.size, apiMs,
                    oldestTs?.toString() ?: "-",
                    newestTs?.toString() ?: "-",
                    nextCursor ?: "(end)",
                )
                AppResult.Success(MessagePage(entities, nextCursor))
            },
            onFailure = { t ->
                val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG).w(t, "← /chat/history FAILED in %.1fms (room=%s, before=%s)", apiMs, room, before)
                AppResult.Error(t.toAppError())
            },
        )
    }

    private fun toEntity(item: ChatHistoryItem, room: String): ChatMessageEntity {
        val localKey = item.client_msg_id ?: ChatMessageEntity.localKeyForServer(item.id)
        return ChatMessageEntity(
            localKey = localKey,
            serverId = item.id,
            clientMsgId = item.client_msg_id,
            uid = item.uid,
            room = room,
            displayName = item.display_name,
            text = item.text,
            illustId = item.illust_id,
            ts = item.ts,
            state = SendState.Delivered,
            replyToUid = item.reply_to?.uid,
            replyToCmid = item.reply_to?.client_msg_id,
            replyToDisplayName = item.reply_to?.display_name,
            replyToText = item.reply_to?.text,
        )
    }

    companion object {
        private const val TAG = "Chat-History"
    }
}

/**
 * Translate transport-level throwables into [AppError]s the UI layer can
 * render via `AppError.toUserMessage()` extension.
 */
internal fun Throwable.toAppError(): AppError = when (this) {
    is HttpException -> when (code()) {
        400 -> AppError.BadRequest(cause = this)
        401 -> AppError.Unauthorized(cause = this)
        403 -> AppError.Forbidden(cause = this)
        404 -> AppError.NotFound(cause = this)
        409 -> AppError.Conflict(cause = this)
        410 -> AppError.Gone(cause = this)
        422 -> AppError.Unprocessable(cause = this)
        429 -> AppError.RateLimited(retryAfterSeconds = null, cause = this)
        503 -> AppError.ServiceUnavailable(cause = this)
        in 500..599 -> AppError.Server(code(), message ?: "Server error", this)
        else -> AppError.Unknown(message ?: "Unknown HTTP error", this)
    }
    is SSLException -> AppError.SecurityError(this)
    is java.net.SocketTimeoutException -> AppError.RequestTimeout(this)
    is IOException -> AppError.NetworkUnavailable(this)
    is com.google.gson.JsonParseException -> AppError.Serialization(this)
    else -> AppError.Unknown(message ?: "Unknown error", this)
}
