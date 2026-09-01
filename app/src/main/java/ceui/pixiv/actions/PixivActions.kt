package ceui.pixiv.actions

import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.AppLevelState
import ceui.pixiv.services.appServices
import ceui.pixiv.api.model.AccountResponse
import ceui.loxia.Novel
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.task.NovelAutoDownload
import ceui.pixiv.widgets.RateAppManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import timber.log.Timber

/**
 * 收藏 / 关注的统一入口。UI 调这里，**立即返回**。
 *
 * 本地状态立刻改掉（用户看到爱心马上变红），请求进队列。真正的网络请求由
 * [PixivActionQueue] 串行、按 2 秒间隔发出，撞 429 会整队冷却并自动重试；进程被杀后
 * 下次启动继续发。成功之后由队列补埋点，终态失败时由队列回滚这里做的乐观更新并提示用户。
 *
 * 埋点刻意**不**在这里发：这一刻请求还没出去，之后可能因为限流打满重试或作品已删除
 * 而终态失败并被回滚，而埋点发出去就撤不回来。
 *
 * 同理**不弹「收藏成功」**：那一刻请求还没发出去，报成功就是骗用户，而失败时队列几分钟后
 * 还会补一个「收藏失败」的 toast 自相矛盾。反馈由爱心本身承担，失败时队列会把它拨回去。
 *
 * ## 一幅作品的两个表示
 *
 * 同一个作品在仓库里同时以 [ObjectPool] 里的 [Illust]（V3 详情 / feeds 条目 / legacy 详情都
 * 按 id 读它）和各列表条目自己的拷贝存在。所以每次收藏态变更都要两管齐下：写 [Illust] 池条目、
 * 发 [Params.LIKED_ILLUST] 广播让各列表把自己的拷贝拨过去。
 * 漏掉任何一路的表现都是「这个页面红了那个页面还是灰的」，而且回滚时同样会漏。
 *
 * 之所以要有这么一层门面：仓库里原本有三套并行的收藏写法（legacy 的 `PixivOperate`、
 * V3 的 `DetailFeedSupport`、小说卡片自己的 scope），乐观更新、埋点、私密收藏设置各写各的，
 * 行为不一致。现在 `PixivOperate.postLike` / `postFollowUser` 也只是本门面的薄封装，
 * 所有收藏 / 关注写操作都从这里进同一条限流队列。
 */
object PixivActions {

    /** 队列是进程级服务（见 [ceui.pixiv.services.ServicesProvider]），这个门面自己无状态，按需取。 */
    private val actionQueue: PixivActionQueue
        get() = Shaft.getContext().appServices().pixivActionQueue

    /**
     * 收藏的默认可见性。
     *
     * 必须读设置：仓库里每一个收藏入口（feed 卡片、小说卡片、桌面小组件、legacy 的
     * PixivOperate）都尊重「私密收藏」开关，这个门面是为了消灭这类不一致才存在的，
     * 自己写死 public 等于把用户明确要求保密的收藏公开挂到主页上。
     */
    @JvmStatic
    fun defaultBookmarkRestrict(): String =
        if (Shaft.sSettings.isPrivateStar) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC

    /**
     * 关注的默认可见性（issue #993），[defaultBookmarkRestrict] 的关注版。
     *
     * 只管**短按 / 自动关注**这条默认路径：长按恒为私密关注的语义不经过它，那些调用点仍
     * 显式传 [Params.TYPE_PRIVATE]。取关不吃 restrict（`postUnFollow` 没有这个参数），
     * 所以取关那一路传什么都一样。
     */
    @JvmStatic
    fun defaultFollowRestrict(): String =
        if (Shaft.sSettings.isPrivateFollow) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC

    // ── 插画 / 漫画 ──────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleIllustBookmark(illust: Illust, restrict: String = defaultBookmarkRestrict()) {
        setIllustBookmark(illust, illust.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setIllustBookmark(
        illust: Illust,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ) {
        setIllustBookmark(illust, bookmark, restrict, autoSnapshot = true)
    }

    private fun setIllustBookmark(
        illust: Illust,
        bookmark: Boolean,
        restrict: String,
        autoSnapshot: Boolean,
    ) {
        if (illust.isBookmarked == bookmark) return
        applyIllustBookmark(
            illustId = illust.id,
            bookmark = bookmark,
            restrict = restrict,
            illust = illust,
            authorId = illust.user?.id,
            authorFollowed = illust.user?.is_followed,
            autoSnapshot = autoSnapshot,
        )
        // 自动快照不再在点击时触发，改为 ActionQueue Succeeded 事件后触发，
        // 避免与收藏/关注写操作“竞争”ObjectPool 数据。
    }

    // ── 批量收藏 / 取消收藏 ──────────────────────────────────────────────────

    /**
     * 批量入口专用的 scope（批量选择页底栏，issue #974）。
     *
     * **必须是 Main**：[writeIllustBookmarkLocally] 最终写的是 [ObjectPool] 里的
     * `MutableLiveData.setValue`，子线程调会抛。
     *
     * **不能用 `Main.immediate`**：那样 `yield()` 会退化成空操作（`isDispatchNeeded` 在主线程
     * 恒为假 → 走 `yieldUndispatched()` → 主线程没有 unconfined 队列可入 → 直接返回「没挂起」），
     * 几千项就会挤在同一个 looper message 里跑完，稳定 ANR。
     *
     * **必须自带 [CoroutineExceptionHandler]**（同 [PixivActionQueue] 的 feedbackScope）：
     * SupervisorJob 只隔离兄弟协程、不吞异常，漏网的抛出会冒到默认处理器上崩进程。
     *
     * **必须活得比调用方久**：批量页入队后立刻 `finish()`，挂在它生命周期上的协程会被连坐取消，
     * 剩下的项就静默丢了 —— 而 toast 已经跟用户说了「已加入队列 N 项」。
     */
    private val bulkScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "bulk bookmark scope crashed") }
    )

    /**
     * [illusts] 里收藏态还不是 [bookmark] 的项数 —— 也就是**真正会发出去的请求数**。
     *
     * UI 要在动手之前就拿到它：勾了 200 项其中 190 项本来就收藏着的话，
     * 「批量收藏 (200 项)」是句假话，用户会照着它去等一个不会发生的进度。
     */
    @JvmStatic
    fun pendingIllustBookmarkCount(illusts: List<Illust>, bookmark: Boolean): Int =
        illusts.count { it.isBookmarked != bookmark }

    /**
     * 批量收藏 / 取消收藏。立即返回**真正入队的项数**（已经是目标态的会被跳过）。
     *
     * 就是循环调 [setIllustBookmark]，刻意不另走一条写路径 —— 仓库原本就是「三套并行的收藏
     * 写法行为各不相同」，这个门面正是为了消灭那类不一致才存在的。请求怎么发、怎么去重、
     * 怎么退避、进程被杀怎么续，全是 `:actionqueue` 的事，这里一件都不重复实现。
     *
     * 门面在这一层只多做两件单条路径不需要的事：
     *  - **分块 [yield]**：每项要写 [ObjectPool] 表示（[Illust] 那一路还带 gson merge）、
     *    发一条跨列表广播、碰一次 MMKV，几千项连着跑完足够掉帧；
     *  - **逐项 try/catch**：上面任何一处抛出来都会让整个循环当场结束，而剩下的项就这么
     *    静默丢了。同仓 [PixivActionQueue] 的事件订阅是同一条理由单独兜每一条事件的。
     */
    @JvmStatic
    @JvmOverloads
    fun setIllustBookmarks(
        illusts: List<Illust>,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ): Int {
        val todo = illusts.filter { it.isBookmarked != bookmark }
        if (todo.isEmpty()) return 0
        bulkScope.launch {
            var failed = 0
            todo.chunked(BULK_CHUNK).forEach { chunk ->
                chunk.forEach { illust ->
                    try {
                        setIllustBookmark(illust, bookmark, restrict, autoSnapshot = false)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        failed++
                        Timber.tag(TAG).e(t, "bulk bookmark failed for illust %d", illust.id)
                    }
                }
                yield()
            }
            if (failed > 0) Timber.tag(TAG).w("%d of %d items failed to enqueue", failed, todo.size)
        }
        return todo.size
    }

    /** [setIllustBookmarks] 的小说版。语义、线程模型、异常兜底完全一致，见那边的注释。 */
    @JvmStatic
    fun pendingNovelBookmarkCount(novels: List<Novel>, bookmark: Boolean): Int =
        novels.count { it.is_bookmarked != bookmark }

    /**
     * [setIllustBookmarks] 的小说版：循环调 [applyNovelBookmark]，返回实际入队条数。
     *
     * 小说这一支**没有**「收藏后自动关注作者」—— 单张小说卡本来就没有（见
     * [setNovelBookmark]），批量不该凭空多出一个副作用。
     *
     * 同理**不走「收藏后自动下载」**（所以调的是 [applyNovelBookmark] 而不是
     * [setNovelBookmark]）：勾几百篇就是几百次抓正文，稳定把自己打到 429；而这个入口
     * 就在批量选择页里，旁边第一颗按钮正是「下载选中小说」，用户想下会自己点。
     * 插画那支同样如此 —— 自动下载只挂在单点收藏的调用点上。
     */
    @JvmStatic
    @JvmOverloads
    fun setNovelBookmarks(
        novels: List<Novel>,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ): Int {
        val todo = novels.filter { it.is_bookmarked != bookmark }
        if (todo.isEmpty()) return 0
        bulkScope.launch {
            var failed = 0
            todo.chunked(BULK_CHUNK).forEach { chunk ->
                chunk.forEach { novel ->
                    try {
                        applyNovelBookmark(novel, bookmark, restrict)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        failed++
                        Timber.tag(TAG).e(t, "bulk bookmark failed for novel %d", novel.id)
                    }
                }
                yield()
            }
            if (failed > 0) Timber.tag(TAG).w("%d of %d novels failed to enqueue", failed, todo.size)
        }
        return todo.size
    }

    /** @param authorFollowed 调用方手上那份作者数据的关注态，见 [autoFollowAuthor]。 */
    private fun applyIllustBookmark(
        illustId: Long,
        bookmark: Boolean,
        restrict: String,
        illust: Illust?,
        authorId: Long?,
        authorFollowed: Boolean?,
        autoSnapshot: Boolean,
    ) {
        writeIllustBookmarkLocally(illustId, bookmark, illust)
        if (bookmark) RateAppManager.onUserEngaged()

        actionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.ILLUST_BOOKMARK,
                dedupeKey = "${PixivActionTypes.ILLUST_BOOKMARK}:$illustId",
                payload = Shaft.sGson.toJson(
                    BookmarkPayload(illustId, bookmark, restrict, autoSnapshot = autoSnapshot)
                ),
            )
        )

        if (bookmark) autoFollowAuthor(authorId, authorFollowed)
    }

    /**
     * 「收藏后自动关注作者」（对齐 legacy postLike / postLikeNovel）。同样走队列 —— 直发的话
     * 它会和队列里同一个作者的关注/取关抢着写 [ObjectPool]，谁后到谁说了算。
     *
     * 收在门面里而不是让每个收藏入口各写一遍：判重要同时看**两边**。
     *
     * @param authorFollowed 调用方手上那份作者数据的关注态。它和 [ObjectPool] 里的记录任意一边
     *                       说已关注就不再发 —— 只信作品里带的那份（legacy postLike 的判据）会给
     *                       刚在别处关注过的作者重复发一次；只信池子则在池里没有这个作者时同样
     *                       会重复发。小说卡片那支原先只判前者，正是这条不一致。
     */
    internal fun autoFollowAuthor(authorId: Long?, authorFollowed: Boolean?) {
        if (authorId == null || authorId <= 0L) return
        if (!Shaft.sSettings.isAutoFollowAfterStar) return
        if (authorFollowed == true || isFollowed(authorId)) return
        setUserFollow(authorId, follow = true)
    }

    // ── 按标签收藏 ──────────────────────────────────────────────────────────

    /**
     * 「按标签收藏」sheet 提交（[ceui.pixiv.ui.bookmark.SelectTagFeedFragment]）。
     *
     * 与不带标签的收藏**共用 dedupeKey**：两者打的是同一个 `bookmark/add` 端点，对同一作品是
     * 互相覆盖而不是叠加的，所以「先在卡片上点了心、再开 sheet 选标签提交」只该发最后那一次。
     *
     * 刻意**不**跟着做自动关注：legacy 的 FragmentSB.submitStar 也没有，这里只把发请求这一步
     * 换成入队，不顺手改变行为。
     */
    @JvmStatic
    fun bookmarkIllustWithTags(illustId: Long, restrict: String, tags: List<String>) {
        writeIllustBookmarkLocally(illustId, true)
        // 自动快照统一由 ActionQueue Succeeded 事件触发。
        RateAppManager.onUserEngaged()
        actionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.ILLUST_BOOKMARK,
                dedupeKey = "${PixivActionTypes.ILLUST_BOOKMARK}:$illustId",
                payload = Shaft.sGson.toJson(
                    BookmarkPayload(illustId, true, restrict, tags.ifEmpty { null }, autoSnapshot = true)
                ),
            )
        )
    }

    @JvmStatic
    fun bindAccountOnline(uid: Long, accountResponse: AccountResponse) {
        // /account/online feeds the Nana7mi lending pool. Lite must neither borrow from that pool
        // nor contribute the signed-in user's AccountResponse to it.
        if (BuildConfig.IS_LITE) return
        if (uid <= 0L) {
            // 会话还没加载完 / 匿名态。入队只会换来服务端恒定的 400 bad_uid，
            // 而且 dedupeKey 会变成 "user_online:0"，把后面真实 uid 的上报也搅浑。
            Timber.tag(TAG).w("bindAccountOnline skipped, invalid uid=%d", uid)
            return
        }
        Shaft.getContext().appServices().accountOnlineReportOutbox.enqueueOnline(uid, accountResponse)
    }

    /**
     * 小说版本的 [bookmarkIllustWithTags]。
     *
     * 与插画那支的差别只有一处：这里**跟着做「收藏后自动下载」**。理由是入口语义 ——
     * 长按小说卡选「按标签收藏」和直接点心是同一个「我要收藏这一篇」，两者一个下载一个
     * 不下载，在用户看来就是开关时灵时不灵。旧值来自 [ObjectPool]，必须在
     * [writeNovelBookmarkLocally] 把它改成 true 之前读，否则本来就收藏着的那些会被重下一遍。
     * 池里没有这部小说时不下载：文件名要靠标题 / 作者 / 系列渲染，只有一个 id 渲不出来 ——
     * 而这个 sheet 恒由某张小说卡拉起，池里一定有那一份。
     */
    @JvmStatic
    fun bookmarkNovelWithTags(novelId: Long, restrict: String, tags: List<String>) {
        val pooled = ObjectPool.get<Novel>(novelId).value
        writeNovelBookmarkLocally(novelId, true)
        RateAppManager.onUserEngaged()
        actionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.NOVEL_BOOKMARK,
                dedupeKey = "${PixivActionTypes.NOVEL_BOOKMARK}:$novelId",
                payload = Shaft.sGson.toJson(
                    BookmarkPayload(novelId, true, restrict, tags.ifEmpty { null })
                ),
            )
        )
        if (pooled != null && pooled.is_bookmarked != true) autoDownloadNovel(pooled)
    }

    // ── 小说 ────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun toggleNovelBookmark(novel: Novel, restrict: String = defaultBookmarkRestrict()) {
        setNovelBookmark(novel, novel.is_bookmarked != true, restrict)
    }

    @JvmStatic
    @JvmOverloads
    fun setNovelBookmark(
        novel: Novel,
        bookmark: Boolean,
        restrict: String = defaultBookmarkRestrict(),
    ) {
        if (novel.is_bookmarked == bookmark) return
        applyNovelBookmark(novel, bookmark, restrict)
        // 收藏后自动下载只在主动收藏（非取消）时触发，同插画那支（#880）。
        if (bookmark) autoDownloadNovel(novel)
    }

    /** 收藏一部小说的三件事：写本地、埋 RateApp、入队。批量入口复用它绕开单点的副作用。 */
    private fun applyNovelBookmark(novel: Novel, bookmark: Boolean, restrict: String) {
        writeNovelBookmarkLocally(novel.id, bookmark, novel)
        if (bookmark) RateAppManager.onUserEngaged()

        actionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.NOVEL_BOOKMARK,
                dedupeKey = "${PixivActionTypes.NOVEL_BOOKMARK}:${novel.id}",
                payload = Shaft.sGson.toJson(BookmarkPayload(novel.id, bookmark, restrict)),
            )
        )
    }

    /**
     * 「收藏后自动下载」的小说版：把整篇正文存成 TXT（[NovelAutoDownload]）。
     *
     * 收在门面里而不是让每个收藏入口各写一遍（插画那支正是散在五个调用点上的）：小说的
     * 收藏入口有小说卡、V3 阅读器、系列页、V3 详情流、按标签收藏 sheet 这几处，逐个补一遍
     * 迟早漏，而漏掉的表现是「从某个页面收藏就不下载」这种没人报得清的不一致。
     *
     * 判 `是否本来就收藏着` 由调用方负责：[setNovelBookmark] 靠开头的早退，
     * [bookmarkNovelWithTags] 靠自己读池里的旧值。
     */
    private fun autoDownloadNovel(novel: Novel) {
        if (!Shaft.sSettings.isAutoDownloadAfterStar) return
        NovelAutoDownload.enqueue(novel)
    }

    // ── 关注 ────────────────────────────────────────────────────────────────

    @JvmStatic
    @JvmOverloads
    fun setUserFollow(
        userId: Long,
        follow: Boolean,
        restrict: String = defaultFollowRestrict(),
    ) {
        // 先落可见性，再落关注态 —— 后者会同步通知 UI 重绘，那时前者必须已经就位。
        FollowVisibility.writeLocal(userId, restrict.takeIf { follow })
        writeUserFollowLocally(userId, follow, restrict)
        if (follow) RateAppManager.onUserEngaged()

        actionQueue.enqueue(
            ActionRequest(
                type = PixivActionTypes.USER_FOLLOW,
                // 合并键只带 userId：同一个人的关注/取关反复横跳只留最后一次。
                dedupeKey = "${PixivActionTypes.USER_FOLLOW}:$userId",
                payload = Shaft.sGson.toJson(FollowPayload(userId, follow, restrict)),
            )
        )
    }

    // ── 本地状态写入（乐观更新与队列回滚共用同一份，保证两边覆盖的表示完全一致）──────

    /**
     * 把收藏态写进这幅作品的每一个共享表示，并广播出去。
     *
     * 幂等：已是目标态的表示原样跳过（`total_bookmarks` 的加减因此不会被重复调用叠加）。
     * 队列回滚时传相反的值再调一次即可，不需要另写一套。
     *
     * @param illust 调用方手上那份实例，仅在池里还没有这幅作品时用作兜底数据源 ——
     *               池里没有就把它放进去，否则 V3 详情页按 id 读池会渲染出一颗灰心。
     */
    internal fun writeIllustBookmarkLocally(
        illustId: Long,
        bookmark: Boolean,
        illust: Illust? = null,
    ) {
        val pooled = ObjectPool.get<Illust>(illustId).value
        val current = pooled ?: illust
        if (current != null && current.is_bookmarked != bookmark) {
            val delta = if (bookmark) 1 else -1
            ObjectPool.update(
                current.copy(
                    is_bookmarked = bookmark,
                    total_bookmarks = current.total_bookmarks?.plus(delta),
                )
            )
        } else if (pooled == null && illust != null) {
            ObjectPool.update(illust)
        }

        broadcast(Params.LIKED_ILLUST, illustId, bookmark)
    }

    /**
     * 小说版本的 [writeIllustBookmarkLocally]。同样幂等，回滚传相反的值复用。
     *
     * 广播必须在这里发、而不是留给各个调用点：小说列表条目的收藏态是 FeedViewModel 自己的一份
     * 拷贝，不读 [ObjectPool]，只认 [Params.LIKED_NOVEL]。此前只有 NovelFeedFragment 在自己的
     * 点击处补了广播，于是从 V3 阅读器或 V3 详情流收藏同一部小说时，别处的小说列表永远不同步；
     * 而队列回滚却是发广播的 —— 一侧发一侧不发，正是插画那支用共用函数消灭掉的那类裂缝。
     *
     * @param novel 调用方手上那份实例，仅在池里还没有这部小说时用作兜底数据源。
     */
    internal fun writeNovelBookmarkLocally(
        novelId: Long,
        bookmark: Boolean,
        novel: Novel? = null,
    ) {
        val current = ObjectPool.get<Novel>(novelId).value ?: novel
        if (current != null && current.is_bookmarked != bookmark) {
            val delta = if (bookmark) 1 else -1
            ObjectPool.update(
                current.copy(
                    is_bookmarked = bookmark,
                    total_bookmarks = current.total_bookmarks?.plus(delta),
                )
            )
        }
        broadcast(Params.LIKED_NOVEL, novelId, bookmark)
    }

    /**
     * 关注态版本的 [writeIllustBookmarkLocally]。同样幂等，回滚传相反的值复用。
     *
     * 只管「关注了没」；「怎么关的」由 [FollowVisibility] 单独存，且已经在
     * [setUserFollow] 里先写好了 —— [ObjectPool] 这一下是**同步**分发给 User 观察者的，
     * 作者栏就在这里重绘，那时可见性必须已经是新的。
     */
    internal fun writeUserFollowLocally(
        userId: Long,
        follow: Boolean,
        restrict: String = Params.TYPE_PUBLIC,
    ) {
        if (follow) {
            Shaft.getContext().appServices().appLevelState.updateFollowUserStatus(
                userId,
                if (restrict == Params.TYPE_PUBLIC) {
                    AppLevelState.FollowUserStatus.FOLLOWED_PUBLIC
                } else {
                    AppLevelState.FollowUserStatus.FOLLOWED_PRIVATE
                },
            )
            ObjectPool.followUser(userId)
        } else {
            Shaft.getContext().appServices().appLevelState.updateFollowUserStatus(
                userId,
                AppLevelState.FollowUserStatus.NOT_FOLLOW,
            )
            ObjectPool.unFollowUser(userId)
        }
        broadcast(Params.LIKED_USER, userId, follow)
    }

    private fun isFollowed(userId: Long): Boolean =
        ObjectPool.get<ceui.loxia.User>(userId).value?.is_followed == true

    /**
     * legacy 的三条跨列表同步广播（[Params.LIKED_ILLUST] / [Params.LIKED_NOVEL] /
     * [Params.LIKED_USER]）共用的载荷契约：id 走 **int**，值放 [Params.IS_LIKED]。
     * 接收侧（[ceui.pixiv.ui.common.FeedLikeSync]、legacy CommonReceiver）一直按 int 读，
     * 这里不能自行加宽，否则对面读到 0。
     *
     * 过去这条广播是**请求成功之后**才发的；改走队列后它必须在点击这一刻就发出去 ——
     * 否则列表要等几分钟（甚至一次冷却）才跟上乐观态。失败时队列回滚会带相反的值再发一次。
     */
    private fun broadcast(action: String, id: Long, liked: Boolean) {
        val ctx = Shaft.getContext() ?: return
        val intent = Intent(action).apply {
            putExtra(Params.ID, id.toInt())
            putExtra(Params.IS_LIKED, liked)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    /**
     * 一批动作全部发完大概几分钟 —— 队列是串行 + 最小间隔的，几百项就是十几分钟起步，
     * 批量入口的确认框要拿它跟用户说清楚，否则会被当成没生效。真正的节奏在
     * [PixivActionQueue] 手里，这里只是门面的转发（UI 不直接碰队列）。
     */
    @JvmStatic
    fun estimatedQueueMinutes(count: Int): Int = actionQueue.estimatedMinutes(count)

    /**
     * 批量写入每让一次主线程处理多少项。按一帧 16ms 的预算倒推的悲观值：单项最贵的一路是
     * `ObjectPool.update(Illust)` 触发的 gson merge，量级到亚毫秒，25 项即使全走最贵路径也还
     * 在一帧之内；再小只会平白多几百次 looper 往返。
     */
    private const val BULK_CHUNK = 25

    private const val TAG = "PixivActions"
}
