package ceui.pixiv.actions

import ceui.lisa.activities.Shaft
import ceui.pixiv.actionqueue.ActionHandler
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.RetryScope
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
 * 把异常翻译成队列能理解的结局。
 *
 * 三个维度，每个都独立影响用户看到的结果：
 * - **能不能再试**：429 / 5xx 等一等就好；404 是作品被删了，试到天荒地老也没用。
 * - **该冻住谁**（[RetryScope]）：429 是账号级速率限制，整队都得停；400 / 401 只说明这一条
 *   （或这一刻的 token）不行，冻整队等于让一条坏行把别人的收藏一起卡住 7.5 分钟。
 * - **算不算一次尝试**（[ActionOutcome.Retry.countsAsAttempt]）：断网时请求根本没离开设备，
 *   不该记在这条动作头上 —— 否则地铁里那几分钟就足够把用户排队的收藏烧成终态失败。
 *
 * @param isOnline 失败发生时设备是否还有网。断网判定只用来「不扣预算」，判错了最坏也就是
 *                 多扣一次或多试几轮，不会丢动作。
 */
internal fun Throwable.toActionOutcome(isOnline: Boolean): ActionOutcome = when (this) {
    is HttpException -> when (val code = code()) {
        // 唯一确定属于账号级速率限制的码：整队一起等，别的动作照发只会接着撞。
        429 -> ActionOutcome.Retry(
            retryAfterMs = retryAfterMs(),
            cause = this,
            scope = RetryScope.QUEUE,
        )
        // 服务端整体不行，跟哪一条无关，同样整队等。
        in 500..599 -> ActionOutcome.Retry(cause = this, scope = RetryScope.QUEUE)
        // 单条请求超时：换下一条多半是好的。
        408 -> ActionOutcome.Retry(cause = this, scope = RetryScope.ACTION)
        // 400 是 pixiv 表达「access token 过期」用的码，正常情况下由
        // TokenFetcherInterceptor 在链内刷新并重放，外面根本看不到。能漏到这里
        // 说明那次刷新自己失败了（刚连上网时 refreshTokenBlocking 超时、oauth 端点
        // 5xx…），而 SessionManager.refreshAccessToken 对任何异常都只是返回 null。
        // 判终态失败的话，用户一次网络抖动就会看到爱心弹回去 —— 而那时 token 多半
        // 已经刷好了，同一个请求两秒后就能成功。宁可按可重试算：真是参数非法，
        // 五次之后照样收敛成终态失败，只是慢一点。
        //
        // 但**只推迟这一条**：refresh token 真的死掉时（改过密码、被撤销），每一条动作
        // 都会 400，而 SessionManager 并不会因此把 isLoggedIn 翻成 false，gate 拦不住。
        // 若按整队冷却算，一次会话失效就能让队列以「每 7.5 分钟弹一次收藏失败」的节奏
        // 空转几小时；按单条算，坏行各自收敛成终态失败，互不连累。
        400, 401, 403 -> ActionOutcome.Retry(cause = this, scope = RetryScope.ACTION)
        else -> ActionOutcome.Fail("HTTP $code", this)
    }
    // 断网 / 超时 / 连接被重置。
    // 离线：请求没发出去，不扣预算，也不必让别的动作白试（gate 也会把队列挂起）。
    // 在线还 IO 失败：是超时、连接重置这类真实故障，正常扣预算，只推迟这一条。
    is IOException -> if (isOnline) {
        ActionOutcome.Retry(cause = this, scope = RetryScope.ACTION)
    } else {
        ActionOutcome.Retry(cause = this, scope = RetryScope.QUEUE, countsAsAttempt = false)
    }
    else -> ActionOutcome.Fail(message ?: javaClass.simpleName, this)
}

/** 读 `Retry-After` 响应头（秒）。pixiv 不一定给，给了就听它的。 */
private fun HttpException.retryAfterMs(): Long? =
    response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L)

/**
 * @param isOnline 请求失败之后现查一次连通性（而不是发请求前查）：要区分的是
 *                 「压根没网」和「有网但这次连接崩了」，只有失败那一刻的状态说明问题。
 * @param deleting 这次是「取消收藏 / 取关」这类**删除**操作。真时 404 判成功。
 *
 * 404 对删除来说不是失败，是「要删的东西已经不在了」—— 目标状态恰恰达成了。
 * [ActionHandler] 的幂等契约（进程被杀后残留的 RUNNING 会被重跑）正需要这一条：pixiv 对重复
 * 收藏返回成功，但对重复取消收藏返回 **404**。不这么判的话，连点爱心时只要第一条已经在飞、
 * 合并吃不掉它，第二条重复的取消就会 404 → 判终态失败 → 回滚把心点回红色并弹「收藏失败」，
 * 而服务端上这个收藏其实已经取消了 —— 界面从此和服务端相反，且不刷新不会自愈。
 * （真机实测：三连点后必现。）
 */
private suspend fun attempt(
    isOnline: () -> Boolean,
    deleting: Boolean = false,
    block: suspend () -> Unit,
): ActionOutcome =
    try {
        block()
        ActionOutcome.Success
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        if (deleting && (t as? HttpException)?.code() == 404) {
            ActionOutcome.Success
        } else {
            t.toActionOutcome(isOnline())
        }
    }

/**
 * 解析 payload；解析不了返回 null。
 *
 * 调用方必须把 null 判成[ActionOutcome.Fail]，**不能**让异常裸抛给队列：队列对未分类的异常
 * 一律按可重试处理，于是一条永远解析不了的行（版本回退后读到新版写入的 json）会把重试预算
 * 和整队节流配额一起烧光，而它无论如何都不可能成功。
 *
 * 失败反馈那一路（[ceui.pixiv.actions.PixivActionQueue] 的 report / rollback）必须用**同一个**
 * 函数解析：执行侧对着解析不了的 payload 判了终态失败，反馈侧若还是裸 `fromJson`，就会在处理
 * 这条失败时自己抛出去 —— 结果是既不回滚也不提示，乐观 UI 永久停在错误状态。
 */
internal inline fun <reified T> PendingAction.parsePayload(): T? =
    runCatching { Shaft.sGson.fromJson(payload, T::class.java) }.getOrNull()

private fun badPayload(action: PendingAction): ActionOutcome =
    ActionOutcome.Fail("unparsable payload: ${action.payload.take(120)}")

internal class IllustBookmarkHandler(private val isOnline: () -> Boolean) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<BookmarkPayload>() ?: return badPayload(action)
        return if (payload.bookmark) {
            attempt(isOnline) { Client.appApi.postBookmark(payload.id, payload.restrict) }
        } else {
            attempt(isOnline, deleting = true) { Client.appApi.removeBookmark(payload.id) }
        }
    }
}

internal class NovelBookmarkHandler(private val isOnline: () -> Boolean) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<BookmarkPayload>() ?: return badPayload(action)
        return if (payload.bookmark) {
            attempt(isOnline) { Client.appApi.addNovelBookmark(payload.id, payload.restrict) }
        } else {
            attempt(isOnline, deleting = true) { Client.appApi.removeNovelBookmark(payload.id) }
        }
    }
}

internal class UserFollowHandler(private val isOnline: () -> Boolean) : ActionHandler {
    override suspend fun execute(action: PendingAction): ActionOutcome {
        val payload = action.parsePayload<FollowPayload>() ?: return badPayload(action)
        return if (payload.follow) {
            attempt(isOnline) { Client.appApi.postFollow(payload.userId, payload.restrict) }
        } else {
            attempt(isOnline, deleting = true) { Client.appApi.postUnFollow(payload.userId) }
        }
    }
}
