package ceui.pixiv.actions

import android.content.Context
import ceui.lisa.R
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.appServices
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.events.EventReporter
import ceui.pixiv.session.SessionManager
import ceui.pixiv.snapshot.AutoSnapshotEngine
import ceui.pixiv.websocket.AppNetworkMonitor
import ceui.pixiv.websocket.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * 装配、启动时机和失败反馈，是唯一持有实例的地方。UI 不直接碰它，走 [PixivActions]。
 *
 * 一个进程只有一份，由 [ceui.lisa.activities.Shaft] 构造并经
 * [ceui.loxia.ServicesProvider.pixivActionQueue] 暴露；构造廉价（不建队列、不起协程），
 * 真正的装配在 [start]。
 */
class PixivActionQueue(app: Context) {

    private val app: Context = app.applicationContext

    private val started = AtomicBoolean(false)

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

    /** 幂等。由 [ceui.lisa.activities.Shaft] 调，必须排在 SessionManager.initialize 之后（gate 要读登录态）。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // 进程级共用那一个 monitor：它按订阅数注册/注销系统 NetworkCallback，各自 new 一个
        // 等于各注册一个回调，与该类「全进程只注册一个」的设计相悖。
        val monitor = AppNetworkMonitor.get(app)
        val isOnline = { monitor.isConnected }

        val instance = ActionQueue.withRoomStore(
            context = app,
            handlers = mapOf(
                PixivActionTypes.ILLUST_BOOKMARK to IllustBookmarkHandler(isOnline),
                PixivActionTypes.NOVEL_BOOKMARK to NovelBookmarkHandler(isOnline),
                PixivActionTypes.USER_FOLLOW to UserFollowHandler(isOnline),
                // Compatibility handler only drains owner-scoped rows written by old builds.
                PixivActionTypes.USER_ONLINE to UserOnlineHandler(),
            ),
            policy = QueuePolicy(minGapMs = MIN_GAP_MS),
            // 未登录时只睡不发。否则退登状态下一整队请求会全部 401，
            // 白白烧完重试次数把用户真实的收藏意图变成终态失败。
            //
            // 没网时同样只睡不发：请求发出去也只会 IOException，而按默认参数 5 次重试
            // 加起来只撑得住 7.5 分钟 —— 坐一趟地铁回来，用户排队的收藏已经被烧成终态
            // 失败并回滚了，而「离线也不丢」正是这个队列存在的理由。真在飞行途中掉线的
            // 那一条由 handler 判成「不计入尝试次数」兜底（见 toActionOutcome）。
            gate = { SessionManager.isLoggedIn && monitor.isConnected },
            // 行按登录用户分账。库是跨登录态持久的：A 排队没发完的收藏，如果不认归属，
            // 会在 B 登录之后用 B 的 token 发出去，收藏进 B 的账号。
            owner = { SessionManager.loggedInUid.toString() },
            onError = { message, t -> Timber.tag(TAG).e(t, message) },
        )
        queue = instance
        observeFailures(instance)
        instance.start()
        observeGateSignals(instance, monitor)
        pruneStaleFailures(instance)
        Timber.tag(TAG).i("action queue started, minGap=%dms", MIN_GAP_MS)
    }

    /**
     * [count] 条动作全部发完大概几分钟。节奏是本对象定的（[MIN_GAP_MS] 串行间隔），
     * 所以估算也留在这里，别让调用方去猜一个常量。
     *
     * 向上取整且至少 1，免得批量确认框上写着「约 0 分钟」。冷却和重试不算在内 ——
     * 这是下界，撞了限流只会更久。
     */
    internal fun estimatedMinutes(count: Int): Int {
        val totalMs = count.toLong() * MIN_GAP_MS
        return maxOf(1L, (totalMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    }

    internal fun enqueue(request: ActionRequest) {
        val instance = queue
        if (instance == null) {
            // 只可能发生在 start 之前就有人点了收藏，正常启动顺序下不会走到。
            Timber.tag(TAG).w("enqueue before start, dropped: %s", request.dedupeKey)
            return
        }
        instance.enqueue(request)
    }

    /**
     * 闸门条件恢复时立刻踢一脚队列。
     *
     * 不踢的话只能等兜底轮询（[QueuePolicy.idlePollMs]，60 秒）：网络刚回来 / 刚登录完，
     * 用户看着一排红心却要再等一分钟才真的发出去，而这恰恰是最可能被系统回收进程的时刻。
     *
     * 用 [ActionQueue.kick] 而不是 `resume()`：后者还会清掉暂停标志，等于让一次网络抖动
     * 顺手解除用户主动按下的暂停。这里要的只是「重新看一眼队列」。
     */
    private fun observeGateSignals(instance: ActionQueue, monitor: NetworkMonitor) {
        feedbackScope.launch {
            monitor.observeConnectivity.collect { online ->
                if (online) instance.kick()
            }
        }
        // 登录 / 换账号：owner 变了，队列要重新按新账号取行。
        feedbackScope.launch {
            withContext(Dispatchers.Main) {
                SessionManager.loggedInAccount.observeForever { instance.kick() }
            }
        }
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
     * 必须排在 [ActionQueue.start] **之前**，而且要 [CoroutineStart.UNDISPATCHED]：
     * [ActionQueue.events] 没有 replay，事件落在没有订阅者的空档里就是永久丢失 ——
     * 界面不回滚，行也不会被 forget 掉。普通 launch 只是「把协程排进 Default 派发队列」，
     * 调用返回时收集者往往还没注册上，先后顺序等于没保证；UNDISPATCHED 会就地把协程体跑到
     * 第一个挂起点，也就是 collect 真正把自己挂进 SharedFlow 之后才返回。
     *
     * 只有 [ActionEvent.Failed] 才回滚：[ActionEvent.Retrying] 之后还会再试，
     * 那时回滚会让界面上的爱心来回跳。
     *
     * 每条事件单独 try/catch —— 一条处理不了的事件（比如版本回退后读到的旧 payload）
     * 不能把整个订阅带走，否则这个进程剩下的时间里所有失败都不再回滚也不再提示。
     */
    private fun observeFailures(instance: ActionQueue) {
        feedbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
            instance.events.collect { event ->
                try {
                    when (event) {
                        is ActionEvent.Succeeded -> withContext(Dispatchers.Main) { report(event) }
                        is ActionEvent.Failed -> {
                            Timber.tag(TAG).w(
                                "action failed: type=%s reason=%s superseded=%b",
                                event.action.type, event.reason, event.supersededByPending,
                            )
                            try {
                                withContext(Dispatchers.Main) { handleFailure(event) }
                            } finally {
                                // 反馈做没做成，这一行都没有别的用处了 —— 必须在 finally 里删。
                                // 放在 handleFailure 之后的话，它一抛异常这行就以 FAILED 留在库里，
                                // 一直堆到下次进程启动才被 pruneStaleFailures 收走。
                                instance.forget(event.action.id)
                            }
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
     * 收藏来排序。发现画像（[ProfileManager]）同理 —— 它决定后续推荐，喂进去的必须是真的
     * 发生过的行为。
     */
    private suspend fun report(event: ActionEvent.Succeeded) {
        val action = event.action
        when (action.type) {
            PixivActionTypes.ILLUST_BOOKMARK -> {
                val payload = action.parsePayload<BookmarkPayload>() ?: return unparsable(action)
                // meta 兜底链:Illust 池 → API。
                // 队列化之前(4.8.3)点击路径自带 API 兜底;收编进队列后只读 Illust 池,
                // 池里没有的收藏事件全部无 meta 上报,操作记录页整页读不出条目(#1010),
                // 且 manga 因取不到 type 全被误报成 illust。
                val illust = ObjectPool.get<Illust>(payload.id).value
                val fetched = if (illust == null) {
                    withContext(Dispatchers.IO) {
                        runCatching { Client.appApi.getIllust(payload.id).illust }.getOrNull()
                    }
                } else null
                // pixiv 把漫画存成 type == "manga" 的 illust，按语义目标分开埋点。
                val type = illust?.type ?: fetched?.type
                val target = if (type == "manga") {
                    EventReporter.Target.MANGA
                } else {
                    EventReporter.Target.ILLUST
                }
                eventReporter.report(
                    if (payload.bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
                    target,
                    payload.id,
                    illust ?: fetched,
                )
                // 画像只吃 Illust（要读 tags 和作者）。池里没有就跳过：进程重启后
                // 补发的那条本来就没有 bean 可读，漏一次画像强化远好过为它多打一次网络。
                if (payload.bookmark) {
                    ObjectPool.get<Illust>(payload.id).value?.let(profileManager::onBookmarkIllust)
                }

                // 事件驱动：收藏被服务端确认成功后再触发自动快照。
                // 此时读取 ObjectPool 里的值已经是“本地乐观态 == 服务端确认态”，
                // 不再与队列竞争数据；只有单点收藏/按标签收藏的 payload 才带 autoSnapshot。
                if (payload.bookmark && payload.autoSnapshot) {
                    val confirmed = ObjectPool.get<Illust>(payload.id).value
                        ?.takeIf { it.is_bookmarked == true }
                        ?: (illust ?: fetched)?.takeIf { it.is_bookmarked == true }
                    confirmed?.let(AutoSnapshotEngine::onBookmarkConfirmed)
                }
            }

            PixivActionTypes.NOVEL_BOOKMARK -> {
                val payload = action.parsePayload<BookmarkPayload>() ?: return unparsable(action)
                // 统一 Novel 池命中优先，进程重启后的补发再走 API 兜底。
                val novel = ObjectPool.get<Novel>(payload.id).value
                    ?: withContext(Dispatchers.IO) {
                        runCatching { Client.appApi.getNovel(payload.id).novel }.getOrNull()
                    }
                eventReporter.report(
                    if (payload.bookmark) EventReporter.Type.BOOKMARK else EventReporter.Type.UNBOOKMARK,
                    EventReporter.Target.NOVEL,
                    payload.id,
                    novel,
                )
            }

            PixivActionTypes.USER_FOLLOW -> {
                val payload = action.parsePayload<FollowPayload>() ?: return unparsable(action)
                // reportFollowUser 自己做 ObjectPool 命中→getUserProfile 兜底的解析，
                // 调用方不用先把 User 取到手 —— 省掉了 UActivity 里那次「等 profile 回来
                // 才敢关注」的等待，那期间页面被销毁的话意图就丢了。
                eventReporter.reportFollowUser(payload.userId, payload.follow)
                if (payload.follow) {
                    profileManager.onFollowUser(payload.userId)
                } else {
                    profileManager.onUnfollowUser(payload.userId)
                }
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

    /**
     * 回滚乐观更新。
     *
     * 写回用的是 [PixivActions] 里那两个「写本地状态」的函数本身，只是传相反的值 ——
     * 乐观更新要覆盖的表示（[Illust] 池条目、
     * 跨列表广播）一个都不能漏，而分成两套写法的必然结局是回滚这一侧漏掉其中一路，
     * 表现是「失败提示弹了但那颗心还红着」。
     */
    private fun rollback(event: ActionEvent.Failed) {
        val action = event.action
        when (action.type) {
            PixivActionTypes.ILLUST_BOOKMARK -> {
                val payload = action.parsePayload<BookmarkPayload>() ?: return unparsable(action)
                // 队列已经确认没有更新的意图压着（supersededByPending），这里再比一次当前值，
                // 挡的是队列之外改过状态的路径（例如详情页刚从服务端刷回了真值）。
                // 池里没有时按 null 处理照常回滚：列表条目持有的是自己的拷贝，
                // 它们只认广播，不回滚就一直红着。
                val current = currentIllustBookmarked(payload.id)
                if (current == null || current == payload.bookmark) {
                    PixivActions.writeIllustBookmarkLocally(payload.id, !payload.bookmark)
                }
                toast(R.string.v3_widget_bookmark_failed)
            }

            PixivActionTypes.NOVEL_BOOKMARK -> {
                val payload = action.parsePayload<BookmarkPayload>() ?: return unparsable(action)
                // 与插画那支同一套守卫与同一个写入函数（含 LIKED_NOVEL 广播，小说列表条目
                // 持有的是自己的拷贝、只认广播）。守卫不通过时连广播也不发 —— 那说明状态
                // 已经被队列之外的路径改过，再拨一次等于用旧结果覆盖服务端刚刷回的真值。
                val current = ObjectPool.get<Novel>(payload.id).value?.is_bookmarked
                if (current == null || current == payload.bookmark) {
                    PixivActions.writeNovelBookmarkLocally(payload.id, !payload.bookmark)
                }
                toast(R.string.v3_widget_bookmark_failed)
            }

            PixivActionTypes.USER_FOLLOW -> {
                val payload = action.parsePayload<FollowPayload>() ?: return unparsable(action)
                val user = ObjectPool.get<User>(payload.userId).value
                // 与收藏那支同样的守卫：当前关注态已经不是我们乐观写进去的那个值时就别动它
                //（提示照弹 —— 用户仍然需要知道这次关注没成）。
                if (user == null || user.is_followed == payload.follow) {
                    // 这次操作作废，本地记的可见性一并退回「不知道」——原来是公开还是私密
                    // 从没随请求带出去过，留空让下次 user/follow/detail 重新填，好过猜一个。
                    FollowVisibility.clearLocal(payload.userId)
                    PixivActions.writeUserFollowLocally(payload.userId, !payload.follow)
                }
                Common.showToast(app.getString(R.string.msg_operation_fail, event.reason))
            }
        }
    }

    /** 这幅作品当前的收藏态；不在池里时返回 null。 */
    private fun currentIllustBookmarked(illustId: Long): Boolean? =
        ObjectPool.get<Illust>(illustId).value?.is_bookmarked

    /**
     * payload 解析不了时的统一出口 —— 只可能是版本回退后读到了新版写入的 json。
     *
     * 执行侧对同一份 payload 已经判过终态失败（见 [parsePayload]），反馈侧必须用同一个宽容的
     * 解析：裸 `fromJson` 会在处理这条失败时自己抛出去，那样既不回滚也不提示，乐观 UI 就永久
     * 停在错误状态了。回滚不了也没关系 —— 界面上的收藏态本来就会在下次从服务端刷新时被纠正。
     */
    private fun unparsable(action: PendingAction) {
        Timber.tag(TAG).e(
            "unparsable payload, skipping feedback: type=%s id=%d",
            action.type, action.id,
        )
    }

    private fun toast(resId: Int) {
        Common.showToast(app.getString(resId))
    }

    /** 发现画像：与本队列同为进程级服务，按需取，不在构造期互相持有（两边构造顺序无关）。 */
    private val profileManager get() = app.appServices().profileManager
    private val eventReporter: EventReporter get() = app.appServices().eventReporter

    private companion object {
        const val TAG = "PixivActionQueue"

        /** pixiv 对收藏/关注的速率限制没有公开文档，2 秒是实测不触发 429 的保守值。 */
        const val MIN_GAP_MS = 2_000L

        const val MS_PER_MINUTE = 60_000L
    }
}
