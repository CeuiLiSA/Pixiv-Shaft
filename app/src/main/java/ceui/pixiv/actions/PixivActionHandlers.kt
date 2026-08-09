package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.PendingAction
import ceui.loxia.Client
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

internal object PixivActionTypes {
    const val ILLUST_BOOKMARK = "illust_bookmark"
    const val NOVEL_BOOKMARK = "novel_bookmark"
    const val USER_FOLLOW = "user_follow"
}

/** @param bookmark true = 收藏，false = 取消收藏 */
internal data class BookmarkPayload(
    val id: Long,
    val bookmark: Boolean,
    val restrict: String,
)

/** @param follow true = 关注，false = 取关 */
internal data class FollowPayload(
    val userId: Long,
    val follow: Boolean,
    val restrict: String,
)

/**
 * 把异常翻译成队列能理解的三种结局。
 *
 * 分类的关键是「再试一次有没有可能成功」：429 和 5xx 等一等就好；404 是作品被删了，
 * 试到天荒地老也没用，还白白占着队首的节流配额。
 */
internal fun Throwable.toActionOutcome(): ActionOutcome = when (this) {
    is HttpException -> when (val code = code()) {
        429 -> ActionOutcome.Retry(retryAfterMs = retryAfterMs(), cause = this)
        408 -> ActionOutcome.Retry(cause = this)
        // 401/403 多半是 access token 过期。TokenFetcherInterceptor 只在 400 上刷新，
        // 所以这里靠重试等下一次刷新；真的退登了会被 gate 拦住，不会空转。
        401, 403 -> ActionOutcome.Retry(cause = this)
        in 500..599 -> ActionOutcome.Retry(cause = this)
        else -> ActionOutcome.Fail("HTTP $code", this)
    }
    // 断网、超时、连接被重置：等网络回来。
    is IOException -> ActionOutcome.Retry(cause = this)
    else -> ActionOutcome.Fail(message ?: javaClass.simpleName, this)
}

/** 读 `Retry-After` 响应头（秒）。pixiv 不一定给，给了就听它的。 */
private fun HttpException.retryAfterMs(): Long? =
    response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L)

private suspend fun attempt(block: suspend () -> Unit): ActionOutcome =
    try {
        block()
        ActionOutcome.Success
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        t.toActionOutcome()
    }

private inline fun <reified T> parse(action: PendingAction): T =
    Shaft.sGson.fromJson(action.payload, T::class.java)

internal class IllustBookmarkHandler : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome = attempt {
        val payload = parse<BookmarkPayload>(action)
        if (payload.bookmark) {
            Client.appApi.postBookmark(payload.id, payload.restrict)
        } else {
            Client.appApi.removeBookmark(payload.id)
        }
    }
}

internal class NovelBookmarkHandler : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome = attempt {
        val payload = parse<BookmarkPayload>(action)
        if (payload.bookmark) {
            Client.appApi.addNovelBookmark(payload.id, payload.restrict)
        } else {
            Client.appApi.removeNovelBookmark(payload.id)
        }
    }
}

internal class UserFollowHandler : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome = attempt {
        val payload = parse<FollowPayload>(action)
        if (payload.follow) {
            Client.appApi.postFollow(payload.userId, payload.restrict)
        } else {
            Client.appApi.postUnFollow(payload.userId)
        }
    }
}
