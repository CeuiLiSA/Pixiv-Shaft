package ceui.pixiv.actions

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.events.EventReporter
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 收藏 / 关注这类写操作的进程级队列宿主。
 *
 * 队列本体 [ActionQueue] 是可注入的普通对象（为了能单测）；这里负责它在 app 里的
 * 单例装配、启动时机和失败反馈，是唯一持有实例的地方。UI 不直接碰它，走 [PixivActions]。
 */
object PixivActionQueue {

    private val initialized = AtomicBoolean(false)

    /**
     * 反馈协程的 scope。
     *
     * 必须自带 [CoroutineExceptionHandler]：这里跑的是回滚和 toast，任何一次
     * gson 解析或 ObjectPool 写入抛异常，都会顺着没有 handler 的 SupervisorJob
     * 冒到默认处理器上崩掉整个进程。
     */
    private val feedbackScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "feedback scope crashed") }
    )

    @Volatile
    private var queue: ActionQueue? = null

    /** 幂等。在 [Shaft] 的 onCreate 里调，必须排在 SessionManager.initialize 之后（gate 要读登录态）。 */
    @JvmStatic
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val instance = ActionQueue.withRoomStore(
            context = context.applicationContext,
            handlers = mapOf(
                PixivActionTypes.ILLUST_BOOKMARK to IllustBookmarkHandler(),
                PixivActionTypes.NOVEL_BOOKMARK to NovelBookmarkHandler(),
                PixivActionTypes.USER_FOLLOW to UserFollowHandler(),
            ),
            policy = QueuePolicy(minGapMs = MIN_GAP_MS),
            // 未登录时只睡不发。否则退登状态下一整队请求会全部 401，
            // 白白烧完重试次数把用户真实的收藏意图变成终态失败。
            gate = { SessionManager.isLoggedIn },
            // 行按登录用户分账。库是跨登录态持久的：A 排队没发完的收藏，如果不认归属，
            // 会在 B 登录之后用 B 的 token 发出去，收藏进 B 的账号。
            owner = { SessionManager.loggedInUid.toString() },
            onError = { message, t -> Timber.tag(TAG).e(t, message) },
        )
        queue = instance
        instance.start()
        observeFailures(instance)
        pruneStaleFailures(instance)
        Timber.tag(TAG).i("action queue started, minGap=%dms", MIN_GAP_MS)
    }

    internal fun enqueue(request: ActionRequest) {
        val instance = queue
        if (instance == null) {
            // 只可能发生在 init 之前就有人点了收藏，正常启动顺序下不会走到。
            Timber.tag(TAG).w("enqueue before init, dropped: %s", request.dedupeKey)
            return
        }
        instance.enqueue(request)
    }

    /**
     * 清掉上个进程留下的终态失败行。
     *
     * 它们的乐观状态活在内存里的 ObjectPool 中，随进程一起没了 —— 重启后界面上的收藏态
     * 本来就是服务端的真值，这些行既回滚不了也没人会去看，只会带着最长 500 字的错误文本
     * 一直堆在库里。live 的失败行由 [handleFailure] 处理完立刻删掉。
     */
    private fun pruneStaleFailures(instance: ActionQueue) {
        feedbackScope.launch {
            try {
                val removed = instance.clearFailed()
                if (removed > 0) Timber.tag(TAG).i("pruned %d stale failed actions", removed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "prune stale failures failed")
            }
        }
    }

    /**
     * 订阅执行结果：成功时补埋点，终态失败时回滚乐观更新。
     *
     * 只有 [ActionEvent.Failed] 才回滚：[ActionEvent.Retrying] 之后还会再试，
     * 那时回滚会让界面上的爱心来回跳。
     *
     * 每条事件单独 try/catch —— 一条处理不了的事件（比如版本回退后读到的旧 payload）
     * 不能把整个订阅带走，否则这个进程剩下的时间里所有失败都不再回滚也不再提示。
     */
    private fun observeFailures(instance: ActionQueue) {
        feedbackScope.launch {
            instance.events.collect { event ->
                try {
                    when (event) {
                        is ActionEvent.Succeeded -> withContext(Dispatchers.Main) { report(event) }
                        is ActionEvent.Failed -> {
                            Timber.tag(TAG).w(
                                "action failed: type=%s reason=%s superseded=%b",
                                event.action.type, event.reason, event.supersededByPending,
                            )
                            withContext(Dispatchers.Main) { handleFailure(event) }
                            // 反馈已经做完，这一行没有别的用处了，别让它在库里堆着。
                            instance.forget(event.action.id)
                        }
                        else -> Unit
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "handling queue event failed")
                }
            }
        }
    }

    /**
     * 服务端确认之后才上报。
     *
     * 不能在点击时就报：那条动作还可能因为 429 打满重试、作品已删除等原因终态失败并被回滚，
     * 而埋点一旦发出去就撤不回来（协议里没有反向事件），社区热度榜就会按一堆从未发生过的
     * 收藏来排序。
     */
    private fun report(event: ActionEvent.Succeeded) {
        val gson = Shaft.sGson
        when (event.action.type) {
            PixivActionTypes.ILLUST_BOOKMARK -> {
                val payload = gson.fromJson(event.action.payload, BookmarkPayload::class.java)
                val illust = ObjectPool.get<Illust>(payload.id).value
                // pixiv 把漫画存成 type == "manga" 的 illust，按语义目标分开埋点。
                val target = if (illust?.type == "manga") {
                    EventReporter.Target.MANGA
                } else {
                    EventReporter.Target.ILLUST
                }
                EventReporter.report(
                    if (payload.bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
                    target,
                    payload.id,
                    illust,
                )
            }

            PixivActionTypes.NOVEL_BOOKMARK -> {
                val payload = gson.fromJson(event.action.payload, BookmarkPayload::class.java)
                EventReporter.report(
                    if (payload.bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
                    EventReporter.Target.NOVEL,
                    payload.id,
                    ObjectPool.get<Novel>(payload.id).value,
                )
            }

            PixivActionTypes.USER_FOLLOW -> {
                val payload = gson.fromJson(event.action.payload, FollowPayload::class.java)
                // reportFollowUser 自己做 ObjectPool 命中→getUserProfile 兜底的解析，
                // 调用方不用先把 User 取到手 —— 省掉了 UActivity 里那次「等 profile 回来
                // 才敢关注」的等待，那期间页面被销毁的话意图就丢了。
                EventReporter.reportFollowUser(payload.userId, payload.follow)
            }
        }
    }

    private fun handleFailure(event: ActionEvent.Failed) {
        if (event.supersededByPending) {
            // 用户在这条失败之前又点了一次，那条马上就要发出去。此时回滚等于用旧结果
            // 覆盖新意图，连提示都不该弹 —— 用户看到的状态就是他最后一次点的那个。
            Timber.tag(TAG).i("skip rollback, superseded: %s", event.action.dedupeKey)
            return
        }
        rollback(event)
    }

    private fun rollback(event: ActionEvent.Failed) {
        val gson = Shaft.sGson
        when (event.action.type) {
            PixivActionTypes.ILLUST_BOOKMARK -> {
                val payload = gson.fromJson(event.action.payload, BookmarkPayload::class.java)
                val illust = ObjectPool.get<Illust>(payload.id).value
                // 队列已经确认没有更新的意图压着（supersededByPending），这里再比一次当前值，
                // 挡的是队列之外改过状态的路径（例如详情页刚从服务端刷回了真值）。
                if (illust != null && illust.is_bookmarked == payload.bookmark) {
                    val delta = if (payload.bookmark) -1 else 1
                    ObjectPool.update(
                        illust.copy(
                            is_bookmarked = !payload.bookmark,
                            total_bookmarks = illust.total_bookmarks?.plus(delta),
                        )
                    )
                }
                toast(R.string.v3_widget_bookmark_failed)
            }

            PixivActionTypes.NOVEL_BOOKMARK -> {
                val payload = gson.fromJson(event.action.payload, BookmarkPayload::class.java)
                val novel = ObjectPool.get<Novel>(payload.id).value
                if (novel != null && novel.is_bookmarked == payload.bookmark) {
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = !payload.bookmark,
                            total_bookmarks = novel.total_bookmarks
                                ?.plus(if (payload.bookmark) -1 else 1),
                        )
                    )
                }
                // 小说列表的条目状态是 FeedViewModel 自己的一份拷贝，不读 ObjectPool，
                // 只能靠这条广播把爱心拨回去（NovelFeedItem.withBookmarked 幂等）。
                broadcastNovelBookmark(payload.id, !payload.bookmark)
                toast(R.string.v3_widget_bookmark_failed)
            }

            PixivActionTypes.USER_FOLLOW -> {
                val payload = gson.fromJson(event.action.payload, FollowPayload::class.java)
                val user = ObjectPool.get<User>(payload.userId).value
                val userId = payload.userId.toInt()
                // 与收藏两支同样的守卫：当前关注态已经不是我们乐观写进去的那个值时就别动它
                //（提示照弹 —— 用户仍然需要知道这次关注没成）。
                if (user == null || user.is_followed == payload.follow) {
                    if (payload.follow) {
                        ObjectPool.unFollowUser(payload.userId)
                        Shaft.appViewModel.updateFollowUserStatus(
                            userId,
                            AppLevelViewModel.FollowUserStatus.NOT_FOLLOW,
                        )
                    } else {
                        ObjectPool.followUser(payload.userId)
                        Shaft.appViewModel.updateFollowUserStatus(
                            userId,
                            AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC,
                        )
                    }
                }
                val ctx = Shaft.getContext()
                if (ctx != null) {
                    Common.showToast(ctx.getString(R.string.msg_operation_fail, event.reason))
                }
            }
        }
    }

    /** 与 NovelFeedFragment.sendNovelLikedBroadcast 同一份契约：id 走 int，值放 IS_LIKED。 */
    private fun broadcastNovelBookmark(novelId: Long, liked: Boolean) {
        val ctx = Shaft.getContext() ?: return
        val intent = Intent(Params.LIKED_NOVEL).apply {
            putExtra(Params.ID, novelId.toInt())
            putExtra(Params.IS_LIKED, liked)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    private fun toast(resId: Int) {
        Shaft.getContext()?.let { Common.showToast(it.getString(resId)) }
    }

    private const val TAG = "PixivActionQueue"

    /** pixiv 对收藏/关注的速率限制没有公开文档，2 秒是实测不触发 429 的保守值。 */
    private const val MIN_GAP_MS = 2_000L
}
