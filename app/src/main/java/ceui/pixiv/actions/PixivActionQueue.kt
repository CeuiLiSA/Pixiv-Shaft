package ceui.pixiv.actions

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Common
import ceui.lisa.viewmodel.AppLevelViewModel
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.actionqueue.QueueState
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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
    private val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        )
        queue = instance
        instance.start()
        observeFailures(instance)
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

    /** 给调试页 / 状态栏用。init 之前为 null。 */
    val state: StateFlow<QueueState>? get() = queue?.state

    suspend fun retryAllFailed(): Int = queue?.retryAllFailed() ?: 0

    suspend fun failedCount(): Int = queue?.failedCount() ?: 0

    /**
     * 终态失败时回滚乐观更新。
     *
     * 只处理 [ActionEvent.Failed]：[ActionEvent.Retrying] 之后还会再试，那时回滚会让
     * 界面上的爱心来回跳。
     */
    private fun observeFailures(instance: ActionQueue) {
        feedbackScope.launch {
            instance.events.collect { event ->
                if (event !is ActionEvent.Failed) return@collect
                Timber.tag(TAG).w("action failed: type=%s reason=%s", event.action.type, event.reason)
                withContext(Dispatchers.Main) { rollback(event) }
            }
        }
    }

    private fun rollback(event: ActionEvent.Failed) {
        val gson = Shaft.sGson
        when (event.action.type) {
            PixivActionTypes.ILLUST_BOOKMARK -> {
                val payload = gson.fromJson(event.action.payload, BookmarkPayload::class.java)
                val illust = ObjectPool.get<Illust>(payload.id).value
                // 只有当前状态仍是「我们乐观写进去的那个值」才回滚 —— 用户可能在失败前
                // 又点了一次，这时候强行回滚会把他最新的意图覆盖掉。
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
                    val delta = if (payload.bookmark) -1 else 1
                    ObjectPool.update(
                        novel.copy(
                            is_bookmarked = !payload.bookmark,
                            total_bookmarks = novel.total_bookmarks?.plus(delta),
                        )
                    )
                }
                toast(R.string.v3_widget_bookmark_failed)
            }

            PixivActionTypes.USER_FOLLOW -> {
                val payload = gson.fromJson(event.action.payload, FollowPayload::class.java)
                val userId = payload.userId.toInt()
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
                val ctx = Shaft.getContext()
                if (ctx != null) {
                    Common.showToast(ctx.getString(R.string.msg_operation_fail, event.reason))
                }
            }
        }
    }

    private fun toast(resId: Int) {
        Shaft.getContext()?.let { Common.showToast(it.getString(resId)) }
    }

    private const val TAG = "PixivActionQueue"

    /** pixiv 对收藏/关注的速率限制没有公开文档，2 秒是实测不触发 429 的保守值。 */
    private const val MIN_GAP_MS = 2_000L
}
