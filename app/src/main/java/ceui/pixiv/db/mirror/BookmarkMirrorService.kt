package ceui.pixiv.db.mirror

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.appServices
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * 收藏镜像引擎：**静默、限速、可续传**地把一个账号的收藏列表整份拉进本地库。
 *
 * ## 它解决什么
 *
 * pixiv 的收藏接口只能从新到旧顺着游标翻，既不能倒序也不能筛（友商 pixez #1323）。
 * 唯一的根治办法是把列表整份镜像到本地，之后所有排序/筛选都在 SQLite 里做。
 * 表设计见 [BookmarkMirrorEntity]，查询见 [BookmarkMirrorQuery]。
 *
 * ## 三条铁律
 *
 * 1. **绝不触发 pixiv 频控。** 全局串行（`limitedParallelism(1)`）：无论有几个书架在排队，
 *    同一时刻只有一个请求在飞，页与页之间恒定 [PAGE_INTERVAL_MS]（默认 5 秒 = 12 次/分，
 *    大约是 pixiv 读接口配额的十分之一）再叠随机抖动。真撞上 429 时不只是退避——
 *    **本次进程内的每页间隔会被永久放大**（[intervalMultiplier]），宁可慢一倍也不再撞第二次。
 * 2. **静默。** 不弹窗、不发通知、不占前台、不打断任何操作；出错只进 Timber。
 *    用户唯一能察觉它的地方，是收藏页上那条可选的进度条。
 * 3. **杀进程能续。** 每翻一页就把游标落盘（[BookmarkMirrorStateEntity.nextUrl]），
 *    下次启动从那一页接着翻。**绝不从头再来**——从头再来不只是慢，更是白白多打几百次
 *    请求去撞第 1 条铁律。
 *
 * ## 生命周期：全量一次，之后只维护
 *
 * ```
 *  用户第一次打开某个收藏页
 *      └─ ensureShelf() 注册这个书架（这就是「开启镜像」的唯一动作，也是隐私边界：
 *         没打开过「悄悄收藏」tab，就永远不会去拉悄悄收藏）
 *  BACKFILLING  一页一页翻到底 …… 每页落盘游标
 *      └─ next_url 为空 → SYNCED，记下 firstCompletedAt
 *  SYNCED       从此只做维护：
 *      ├─ 增量：只翻表头几页，连撞 [KNOWN_STREAK_STOP] 条已知的就停（几秒钟的事）
 *      ├─ 本地：收藏/取消收藏成功后直接改这一行，连请求都不用发
 *      └─ 重扫：[FULL_SWEEP_INTERVAL_MS] 到期再走一次全量，用代号差删掉
 *               「在别处取消了收藏」的行——这是唯一能发现远端删除的机制
 * ```
 *
 * ## 为什么是进程内长活协程而不是 WorkManager
 *
 * 首次回填是小时级的活（3 万条 ÷ 30 条/页 × 5 秒 ≈ 80 分钟），而 WorkManager 的单次
 * Worker 有 10 分钟执行上限，拆成周期任务又会被系统按电量/待机随意推迟到几小时后。
 * 既然进度本来就每页落盘、随时可续，跟着进程活着最简单也最可控：用户用 app 的时候它慢慢
 * 往前挪，用户不用了它就停在断点上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkMirrorService(app: Context) {

    private val appContext: Context = app.applicationContext

    private val dao: BookmarkMirrorDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.getAppDatabase(appContext).bookmarkMirrorDao()
    }

    /**
     * 单线程串行：**全局限速的物理保证**。所有书架、所有本地维护共用这一条流水线，
     * 不存在「两个书架同时在翻页」把速率翻倍的可能，`activeRun` 这类裸字段也因此不需要同步。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "引擎协程崩溃(已吞)") }
    )

    /** 唤醒信号。CONFLATED：连着 kick 十次和一次等价，醒来后自己会把该做的都做掉。 */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null

    /** 被用户「刚看过」而需要立刻做一次增量维护的书架。 */
    private val maintenanceRequested = ConcurrentHashMap.newKeySet<String>()

    /** 正在进行的增量/重扫轮次（只有引擎线程读写）。 */
    private var activeRun: MirrorRun? = null

    /**
     * 撞过 429 之后本进程内的限速放大系数。**只增不减**：这一次会话里既然已经证明
     * 5 秒还是太快，就没有理由再赌一次。进程重启回到 1.0（换了网络环境/时段，重新试探）。
     */
    @Volatile
    private var intervalMultiplier: Double = 1.0

    /**
     * 上一次网络请求**完成**的时刻，全局共享（不分书架）。限速的唯一真相来源。
     *
     * 曾经只靠「翻完一页 → delay(5s)」来限速，那是有洞的：一轮跑完返回的是 `Tick.Idle`，
     * 而 `awaitWakeup` 撞上 CONFLATED 通道里一个还没消费的 kick 会立刻返回，下一个书架的
     * 第一页就**零间隔**发了出去。真机日志里抓到过：`[public] MAINTAIN 结束` 之后 2ms
     * 就是 `[private] 开始增量维护`。四个书架依次收尾时能连着打出一串请求 —— 而不触发
     * pixiv 频控是这套系统的第一条铁律，不能靠「正常路径上恰好会 delay」来保证。
     * 改成在**每次发请求之前**对着这个时刻补足间隔，无论从哪条路走到发请求那一步都成立。
     */
    @Volatile
    private var lastRequestFinishedAt: Long = 0L

    private val _activeShelfKey = MutableStateFlow<String?>(null)

    /** 当前正在翻页的书架（null = 空闲）。界面上的「正在同步…」用它。 */
    val activeShelfKey: StateFlow<String?> = _activeShelfKey.asStateFlow()

    // ─────────────────────────── 对外 API ───────────────────────────

    /** 由 [Shaft] 的延迟初始化调用。幂等。 */
    fun start() {
        if (loopJob?.isActive == true) return
        Timber.tag(TAG).i("引擎启动 interval=%dms", PAGE_INTERVAL_MS)
        loopJob = scope.launch { loop() }
        kick("start")
    }

    /**
     * 注册（并唤醒）一个书架 —— **这是开启镜像的唯一入口**。
     *
     * 语义刻意是「用户打开了这个收藏页」而不是「app 决定要镜像什么」：
     * - 隐私边界天然正确：没点开过「悄悄收藏」tab，就绝不会有请求去拉悄悄收藏；
     * - 灵活：插画/小说、公开/悄悄、甚至将来别人的收藏，都只是不同的 [BookmarkShelf]，
     *   引擎一行不用改；
     * - 幂等：已注册的书架只是被标记为「该做一次增量维护了」。
     */
    fun ensureShelf(shelf: BookmarkShelf, reason: String) {
        if (!isFeatureEnabled()) return
        if (shelf.ownerUid <= 0L) return
        scope.launch {
            val existing = dao.findState(shelf.key)
            if (existing == null) {
                val now = System.currentTimeMillis()
                dao.upsertState(newState(shelf, now))
                Timber.tag(TAG).i("注册书架 %s（原因：%s）→ 准备首次全量回填", shelf.label, reason)
            } else {
                // 不把 dao.countOf() 直接写进日志参数：Timber 的参数是**先求值再传**的，
                // release 包没 plant 任何 tree，这句什么都不打印，却仍会为它查一次库——
                // 而本方法在每次打开收藏页时都会走到。
                Timber.tag(TAG).d(
                    "书架 %s 已注册 phase=%s（原因：%s）",
                    shelf.label, MirrorPhase.name(existing.phase), reason,
                )
            }
            // 刚补过就别再补一次。本方法是「用户在看这个书架」的信号，而这个信号是**高频**的：
            // 收藏库顶部的公开/悄悄切换来回点几下就是几次调用，每次都排一轮增量 = 每次都多打
            // 两页请求。收藏不会在这么短的时间里变出新东西，节流掉纯赚。
            val state = existing ?: dao.findState(shelf.key)
            val syncedAgo = state?.lastSyncedAt?.takeIf { it > 0L }
                ?.let { System.currentTimeMillis() - it }
            if (syncedAgo != null && syncedAgo < MIN_MAINTENANCE_GAP_MS) {
                Timber.tag(TAG).d(
                    "书架 %s %ds 前刚补过，本次不排增量", shelf.label, syncedAgo / 1000,
                )
                return@launch
            }
            maintenanceRequested += shelf.key
            kick(reason)
        }
    }

    /** 唤醒循环（别处发生了值得干活的事）。 */
    fun kick(reason: String) {
        Timber.tag(TAG).v("kick: %s", reason)
        wakeup.trySend(Unit)
    }

    /** 观察某个书架的同步状态（界面上的进度条 / 「已镜像 N 件」）。 */
    fun observeState(ownerUid: Long): Flow<List<BookmarkMirrorStateEntity>> = dao.observeStates(ownerUid)

    fun observeCount(shelf: BookmarkShelf): Flow<Int> = dao.observeCount(shelf.key)

    /** 这个账号名下**任一**书架的行数变化（页面可就地切书架，按 uid 订一次就够）。 */
    fun observeOwnerCount(ownerUid: Long): Flow<Int> = dao.observeOwnerCount(ownerUid)

    /** 阻塞式读一份状态快照（调用方负责切 IO）。 */
    fun readState(shelf: BookmarkShelf): BookmarkMirrorStateEntity? = dao.findState(shelf.key)

    /**
     * 这个书架**完整同步过至少一次**了吗 —— 也就是「本地这份能不能当作全量来用」。
     *
     * 导航要用它决定点收藏入口是进本地库还是进原始列表，所以刻意做成同步的：那是主线程上
     * 的一次主键点查，表里最多四行，代价远小于为它引一层异步。任何异常（库还没建好、
     * 迁移中、磁盘故障）一律当作「没准备好」，让调用方回落到不依赖镜像的老路径 ——
     * 导航绝不能因为一个附加功能而崩。
     */
    fun isShelfReady(shelf: BookmarkShelf): Boolean = try {
        isFeatureEnabled() && dao.findState(shelf.key)?.isFirstSyncDone == true
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "读取书架就绪状态失败，按未就绪处理")
        false
    }

    /**
     * 推倒重来：清空这个书架的镜像并重新全量回填。
     *
     * 只有用户明确要求（设置里的「重建收藏镜像」）才该调 —— 它意味着重新打几百次请求。
     */
    fun rebuildShelf(shelf: BookmarkShelf) {
        scope.launch {
            Timber.tag(TAG).w("重建书架 %s：清空 %d 行并重新回填", shelf.label, dao.countOf(shelf.key))
            dao.clearShelf(shelf.key)
            dao.upsertState(newState(shelf, System.currentTimeMillis()))
            activeRun = null
            kick("rebuild")
        }
    }

    /** 关掉某个书架的镜像并清空它（用户在设置里关闭，或换号清理）。 */
    fun dropShelf(shelf: BookmarkShelf) {
        scope.launch {
            Timber.tag(TAG).i("移除书架 %s", shelf.label)
            dao.clearShelf(shelf.key)
            dao.deleteState(shelf.key)
            maintenanceRequested -= shelf.key
            if (activeRun?.shelf == shelf) activeRun = null
        }
    }

    // ─────────────────── 本地维护（不发请求就能把表改对） ───────────────────

    /**
     * 收藏被服务端确认成功后就地插到表头。
     *
     * 这条路径是「同步完成过一次，以后只维护」的一半：用户在 app 里的每一次收藏都
     * 立刻反映进镜像表，**一次网络请求都不用多打**。另一半（在别的设备/网页端做的收藏）
     * 才需要靠增量维护去发现。
     */
    fun onIllustBookmarked(illust: Illust, restrict: MirrorRestrict) {
        upsertLocally(MirrorContentType.ILLUST, illust.id, restrict) { shelf, seq, generation, now ->
            BookmarkMirrorMapper.fromIllust(shelf, illust, seq, generation, now)
        }
    }

    fun onNovelBookmarked(novel: Novel, restrict: MirrorRestrict) {
        upsertLocally(MirrorContentType.NOVEL, novel.id, restrict) { shelf, seq, generation, now ->
            BookmarkMirrorMapper.fromNovel(shelf, novel, seq, generation, now)
        }
    }

    /**
     * 取消收藏被确认后就地删除。
     *
     * 跨公开/悄悄两个书架删：调用点拿到的 restrict 是「本次操作用的默认可见性」，
     * 未必是当初收藏时用的那个，按它删会漏。作品 id 上有索引，两架一起删也是两次点查。
     */
    fun onUnbookmarked(contentType: MirrorContentType, targetId: Long) {
        if (!isFeatureEnabled()) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        scope.launch {
            val removed = dao.deleteTarget(uid, contentType.code, targetId)
            if (removed > 0) {
                dao.deleteTargetTags(shelfKeysOf(uid, contentType), targetId)
                Timber.tag(TAG).d("本地取消收藏：%s#%d 已从镜像移除(%d 行)", contentType.tag, targetId, removed)
            }
        }
    }

    private fun upsertLocally(
        contentType: MirrorContentType,
        targetId: Long,
        restrict: MirrorRestrict,
        build: (BookmarkShelf, Long, Int, Long) -> MirrorRow,
    ) {
        if (!isFeatureEnabled()) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        val shelf = BookmarkShelf(uid, contentType, restrict)
        scope.launch {
            val shelfKeys = shelfKeysOf(uid, contentType)
            // 一个书架都没开镜像 = 这个功能对这台设备还没启用，什么都不做。
            if (shelfKeys.none { dao.findState(it) != null }) return@launch

            // 同一件作品可能刚从另一种可见性改过来（公开↔悄悄），先把另一架上的旧行清掉，
            // 否则一件作品会在两个书架里各留一份。
            // **删除必须在「目标书架是否注册」之前无条件做**：只镜像了公开收藏的用户把某张图
            // 改成悄悄收藏时，目标（悄悄）书架没注册，可写的行确实没有——但公开那一行已经
            // 不成立了，早退会把它留在库里，直到 14 天后的全量重扫才发现。
            dao.deleteTarget(uid, contentType.code, targetId)
            dao.deleteTargetTags(shelfKeys, targetId)

            // 目标书架没开镜像：删掉旧行就是全部该做的事。
            val state = dao.findState(shelf.key) ?: return@launch

            val now = System.currentTimeMillis()
            val opened = openHeadBlock(state, now)
            // 代号取**现读**的 state.generation，不是构造期的快照：全量扫描一开跑就把代号 +1，
            // 收尾时按 `generation < 当前代号` 删失联行。现读到的就是「这一轮正在用的代号」，
            // 于是「扫描已经走过表头之后才发生的本地收藏」也带着新代号，不会被收尾误删。
            val built = build(shelf, opened.blockTop, state.generation, now)
            dao.writePage(listOf(built.row), built.tags)
            Timber.tag(TAG).d(
                "本地收藏入镜像：%s seq=%d（现有 %d 行）",
                shelf.label, opened.blockTop, dao.countOf(shelf.key),
            )
        }
    }

    // ─────────────────────────── 主循环 ───────────────────────────

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val outcome = try {
                tick()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // tick() 内部已经把所有可预期的失败翻译成 Tick 了，走到这里说明是
                // 意料之外的（库损坏、OOM…）。绝不能让它把循环带走：那样这个进程剩下的
                // 时间里镜像就彻底停了，而且一声不响。
                Timber.tag(TAG).e(t, "tick 意外失败，退避后继续")
                Tick.Wait(UNEXPECTED_ERROR_BACKOFF_MS)
            }
            when (outcome) {
                // 刚翻完一页不在这里 delay：节奏统一由 awaitRateLimitWindow 在**发请求前**
                // 补足。两处各算一份间隔（还各带一次随机抖动）只会让实际节奏说不清楚，
                // 而真正需要保证的是「两次请求之间隔够」，不是「两次 tick 之间隔够」。
                is Tick.Fetched -> Unit
                is Tick.Wait -> awaitWakeup(outcome.ms)
                Tick.Idle -> awaitWakeup(IDLE_POLL_MS)
            }
        }
    }

    /** 睡到超时或被 [kick] 唤醒，谁先到算谁。 */
    private suspend fun awaitWakeup(timeoutMs: Long) {
        if (_activeShelfKey.value != null) _activeShelfKey.value = null
        select {
            wakeup.onReceive { }
            onTimeout(timeoutMs.coerceAtLeast(1_000L)) { }
        }
    }

    /** 一次心跳：要么翻一页，要么说明为什么不翻。 */
    private suspend fun tick(): Tick {
        if (!isFeatureEnabled()) return Tick.Idle
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return Tick.Idle
        if (!isOnline()) {
            Timber.tag(TAG).v("离线，暂停镜像")
            return Tick.Idle
        }

        val now = System.currentTimeMillis()
        val states = dao.allStates().filter { it.ownerUid == uid }
        if (states.isEmpty()) return Tick.Idle

        // 冷却是**全局**的：429 限的是这个 IP/账号，不是某个书架，
        // 换一个书架接着发只会接着撞。
        val cooldownUntil = states.maxOf { it.cooldownUntil }
        if (cooldownUntil > now) {
            val wait = min(cooldownUntil - now, MAX_SINGLE_WAIT_MS)
            Timber.tag(TAG).d("限流冷却中，还要等 %ds", (cooldownUntil - now) / 1000)
            return Tick.Wait(wait)
        }

        val job = pickJob(states, now) ?: run {
            activeRun = null
            return Tick.Idle
        }
        return runOnePage(job)
    }

    /**
     * 挑下一件该做的事。顺序即优先级：
     *
     * 1. **续上没跑完的全量**（回填 / 重扫）—— 半截的活最该先做完，它决定了「表是不是完整的」；
     * 2. **没开过头的回填**；
     * 3. **用户刚看过的书架做增量**（他正盯着这个列表，新收藏应该马上出现）；
     * 4. **到期的例行增量**；
     * 5. **到期的全量重扫**（唯一能发现「在别处取消了收藏」的机制，所以放最后：它最贵）。
     */
    private fun pickJob(states: List<BookmarkMirrorStateEntity>, now: Long): MirrorJob? {
        fun shelfOf(state: BookmarkMirrorStateEntity) = state.shelf

        // 手上还有没跑完的一轮就接着跑 —— **必须排在所有条件之前**。
        // 增量维护不改 phase（仍是 SYNCED），开跑时又把「该维护了」的标记消费掉了，
        // 所以下面那些「该不该开一轮」的条件对它一条都不成立：少了这一步，维护会在
        // 翻完第一页之后被静默丢掉，只补了表头 30 条就再也不动（真机实测过）。
        activeRun?.let { run ->
            val state = states.firstOrNull { it.shelfKey == run.shelf.key }
            // 代号对得上才算同一轮。书架被重建时代号会清零，在**发请求之前**就认出来，
            // 省掉「先打一次请求、拿回来再被守卫丢掉」那一发（runOnePage 里那道守卫仍留着：
            // 它兜的是请求**在途**期间发生的重建，这里兜不到）。
            if (state != null && state.generation == run.generation) {
                return MirrorJob(run.shelf, state, run.mode)
            }
            // 书架被移除（换号 / 用户关掉镜像）或被重建：丢掉这一轮
            activeRun = null
        }

        states.firstOrNull { it.phase == MirrorPhase.BACKFILLING }?.let { state ->
            shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.BACKFILL) }
        }
        states.firstOrNull { it.phase == MirrorPhase.RESWEEPING }?.let { state ->
            shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.SWEEP) }
        }
        states.firstOrNull { it.phase == MirrorPhase.NEVER }?.let { state ->
            shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.BACKFILL) }
        }
        states.firstOrNull { it.phase == MirrorPhase.SYNCED && maintenanceRequested.contains(it.shelfKey) }
            ?.let { state -> shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.MAINTAIN) } }
        states.firstOrNull {
            it.phase == MirrorPhase.SYNCED && now - it.lastSyncedAt > MAINTENANCE_INTERVAL_MS
        }?.let { state -> shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.MAINTAIN) } }
        states.firstOrNull {
            it.phase == MirrorPhase.SYNCED && now - it.lastFullSweepAt > FULL_SWEEP_INTERVAL_MS
        }?.let { state -> shelfOf(state)?.let { return MirrorJob(it, state, MirrorMode.SWEEP) } }
        return null
    }

    /**
     * 翻一页、写库、落游标。**每一页都是一个完整的、可被中断的单位**——
     * 写库在一个事务里，游标在写库成功之后才落，所以最坏情况是下次重放这一页（幂等）。
     */
    private suspend fun runOnePage(job: MirrorJob): Tick {
        val shelf = job.shelf
        var state = job.state

        // 进入一轮新的增量/重扫：开一个新号段（见 headSeqCursor 的文档）
        val run = activeRun?.takeIf { it.shelf == shelf && it.mode == job.mode } ?: run {
            val startedState = onRunStart(shelf, state, job.mode)
            state = startedState.state
            MirrorRun(
                shelf = shelf,
                mode = job.mode,
                generation = startedState.state.generation,
                cursor = startedState.cursor,
                headCursor = startedState.state.headSeqCursor,
                backfillSeq = startedState.state.nextBackfillSeq,
            ).also {
                // 续传时把页码/条数接上库里的值，日志里的「第 N 页」才是这一轮**累计**的页码。
                // 否则杀进程重来的那次会从「第 1 页」重新数，看上去像是又从头拉了一遍。
                it.pagesDone = startedState.state.pagesThisRun
                it.itemsSeen = startedState.state.itemsThisRun
                activeRun = it
            }
        }

        _activeShelfKey.value = shelf.key
        val pageNo = run.pagesDone + 1
        Timber.tag(TAG).d(
            "[%s] %s 第 %d 页 cursor=%s",
            shelf.label, job.mode, pageNo, abbreviate(run.cursor),
        )

        awaitRateLimitWindow(shelf)

        val startedAt = System.currentTimeMillis()
        val page = try {
            fetcherFor(shelf).load(run.cursor)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 失败也要记账：失败的请求同样占了 pixiv 的配额，尤其撞 429 时更不能立刻重来。
            lastRequestFinishedAt = System.currentTimeMillis()
            return onPageFailed(shelf, state, t)
        }
        lastRequestFinishedAt = System.currentTimeMillis()
        val latencyMs = System.currentTimeMillis() - startedAt

        // ⚠️ 取回一页之后**必须重新确认这一轮还算数**。
        // 网络那一步（Retrofit suspend / replayNextUrl 的 withContext(IO)）会把
        // limitedParallelism(1) 的槽位让出去，这几百毫秒里 rebuildShelf / dropShelf 的
        // scope.launch 完全可能插进来跑完。不校验的话：用户点了「重建本地镜像」，这一页
        // 会带着**重建前**的 state 快照写回去，把刚建好的干净状态连游标带 phase 一起覆盖，
        // 重建静默失效；dropShelf 则会把行写回一个已经被移除的书架。
        // 用 generation 作这一轮的身份：正常翻页不会动它，重建会把它清零，移除会让整行消失。
        val latest = dao.findState(shelf.key)
        if (latest == null || latest.generation != state.generation) {
            Timber.tag(TAG).w(
                "[%s] 这一页取回期间书架被重建/移除（generation %d → %s），丢弃本页",
                shelf.label, state.generation, latest?.generation?.toString() ?: "已移除",
            )
            activeRun = null
            _activeShelfKey.value = null
            return Tick.Idle
        }
        // 用最新的一份继续：期间可能有本地收藏抬高过号段上限（openHeadBlock），
        // 拿旧快照 copy 回去会把那次抬升抹掉。
        state = latest

        val now = System.currentTimeMillis()
        val written = writePage(shelf, state, run, page, now)

        run.pagesDone = pageNo
        run.itemsSeen += page.items.size
        run.newItems += written.newCount
        run.knownStreak = if (written.newCount == 0) run.knownStreak + page.items.size else 0
        // 防呆：服务端若把 next_url 原样回来（协议异常 / 中间层缓存），照翻下去就是
        // 每 5 秒一次的永动机。判成到底，让这一轮正常收尾。
        val stuckCursor = page.nextUrl != null && page.nextUrl == run.cursor
        if (stuckCursor) {
            Timber.tag(TAG).w("[%s] next_url 与当前游标相同，判定到底以免空转", shelf.label)
        }
        run.cursor = if (stuckCursor) null else page.nextUrl
        run.headCursor = written.headCursor
        run.backfillSeq = written.backfillSeq

        Timber.tag(TAG).i(
            "[%s] %s 第 %d 页 ← %d 条(新 %d) %dms 累计 %d 条 连续已知 %d 下一页=%s",
            shelf.label, job.mode, pageNo, page.items.size, written.newCount, latencyMs,
            run.itemsSeen, run.knownStreak, if (run.cursor == null) "无(到底)" else "有",
        )

        state = persistProgress(shelf, state, run, now)

        val finished = when {
            run.cursor == null -> FinishReason.REACHED_END
            // 增量只碰表头：连着撞到足够多条已知的就收工（几秒钟的事，不是几十分钟）
            job.mode == MirrorMode.MAINTAIN && run.knownStreak >= KNOWN_STREAK_STOP -> FinishReason.KNOWN_STREAK
            job.mode == MirrorMode.MAINTAIN && run.pagesDone >= MAINTAIN_MAX_PAGES -> FinishReason.PAGE_BUDGET
            else -> null
        }
        if (finished != null) {
            onRunFinished(shelf, state, job.mode, run, finished, now)
            activeRun = null
            _activeShelfKey.value = null
            return Tick.Idle
        }
        return Tick.Fetched
    }

    /** 一页落库：先算序号（已有的沿用，新的从号段里发），再一个事务写主表 + 标签表。 */
    private fun writePage(
        shelf: BookmarkShelf,
        state: BookmarkMirrorStateEntity,
        run: MirrorRun,
        page: FetchedPage,
        now: Long,
    ): PageWriteResult {
        if (page.items.isEmpty()) return PageWriteResult(0, run.headCursor, run.backfillSeq)

        val ids = page.items.map { it.id }
        val existing = dao.existingSeqs(shelf.key, ids).associate { it.targetId to it.bookmarkSeq }

        var backfillSeq = run.backfillSeq
        var headCursor = run.headCursor
        var newCount = 0
        val rows = ArrayList<BookmarkMirrorEntity>(page.items.size)
        val tags = ArrayList<BookmarkMirrorTagEntity>(page.items.size * 8)

        page.items.forEach { item ->
            val known = existing[item.id]
            val seq = when {
                // 已经镜像过：**原样保留序号**。重新编号 = 把用户的收藏顺序打乱。
                known != null -> known
                // 首次全量：从 0 往下发（0, -1, -2 …），于是 DESC = 官方顺序、ASC = 倒序
                run.mode == MirrorMode.BACKFILL -> backfillSeq--
                // 增量/重扫里遇到的真·新收藏：从本轮号段顶往下发，先遇到的（更新的）拿更大的号
                else -> headCursor--
            }
            if (known == null) newCount++
            val built = item.toRow(seq, state.generation, now)
            rows += built.row
            tags += built.tags
        }

        dao.writePage(rows, tags)
        return PageWriteResult(newCount, headCursor, backfillSeq)
    }

    private fun persistProgress(
        shelf: BookmarkShelf,
        state: BookmarkMirrorStateEntity,
        run: MirrorRun,
        now: Long,
    ): BookmarkMirrorStateEntity {
        val next = state.copy(
            nextUrl = run.cursor,
            nextBackfillSeq = if (run.mode == MirrorMode.BACKFILL) run.backfillSeq else state.nextBackfillSeq,
            headSeqCursor = run.headCursor,
            pagesThisRun = run.pagesDone,
            itemsThisRun = run.itemsSeen,
            consecutiveFailures = 0,
            lastError = null,
            updatedAt = now,
        )
        dao.upsertState(next)
        return next
    }

    /** 一轮开始：定下起点游标、代号与号段。 */
    private fun onRunStart(
        shelf: BookmarkShelf,
        state: BookmarkMirrorStateEntity,
        mode: MirrorMode,
    ): RunStart {
        val now = System.currentTimeMillis()
        return when (mode) {
            MirrorMode.BACKFILL -> {
                if (state.phase == MirrorPhase.BACKFILLING) {
                    // 续上：游标就是上次落盘的那个
                    Timber.tag(TAG).i(
                        "[%s] 续上未完成的全量回填：从第 %d 页之后继续（已镜像 %d 行）",
                        shelf.label, state.pagesThisRun, dao.countOf(shelf.key),
                    )
                    RunStart(state, state.nextUrl)
                } else {
                    val next = state.copy(
                        phase = MirrorPhase.BACKFILLING,
                        nextUrl = null,
                        generation = state.generation + 1,
                        nextBackfillSeq = 0L,
                        pagesThisRun = 0,
                        itemsThisRun = 0,
                        updatedAt = now,
                    )
                    dao.upsertState(next)
                    Timber.tag(TAG).i("[%s] 开始首次全量回填 generation=%d", shelf.label, next.generation)
                    RunStart(next, null)
                }
            }

            MirrorMode.SWEEP -> {
                if (state.phase == MirrorPhase.RESWEEPING) {
                    Timber.tag(TAG).i("[%s] 续上未完成的全量重扫 generation=%d", shelf.label, state.generation)
                    RunStart(state, state.nextUrl)
                } else {
                    val block = state.headBlockCeiling + HEAD_SEQ_BLOCK
                    val next = state.copy(
                        phase = MirrorPhase.RESWEEPING,
                        nextUrl = null,
                        generation = state.generation + 1,
                        headBlockCeiling = block,
                        headSeqCursor = block,
                        pagesThisRun = 0,
                        itemsThisRun = 0,
                        updatedAt = now,
                    )
                    dao.upsertState(next)
                    Timber.tag(TAG).i(
                        "[%s] 开始全量重扫 generation=%d（上次重扫距今 %d 天）",
                        shelf.label, next.generation,
                        if (state.lastFullSweepAt > 0) (now - state.lastFullSweepAt) / 86_400_000L else -1L,
                    )
                    RunStart(next, null)
                }
            }

            MirrorMode.MAINTAIN -> {
                // 增量不改 phase（仍是 SYNCED）：它随时可以被打断重来，表始终是可用的。
                // 但号段必须先落盘抬高，否则被杀之后下一轮会重用同一段号。
                val block = state.headBlockCeiling + HEAD_SEQ_BLOCK
                val next = state.copy(
                    headBlockCeiling = block,
                    headSeqCursor = block,
                    pagesThisRun = 0,
                    itemsThisRun = 0,
                    updatedAt = now,
                )
                dao.upsertState(next)
                maintenanceRequested -= shelf.key
                Timber.tag(TAG).i("[%s] 开始增量维护（只走表头，最多 %d 页）", shelf.label, MAINTAIN_MAX_PAGES)
                RunStart(next, null)
            }
        }
    }

    private fun onRunFinished(
        shelf: BookmarkShelf,
        state: BookmarkMirrorStateEntity,
        mode: MirrorMode,
        run: MirrorRun,
        reason: FinishReason,
        now: Long,
    ) {
        var next = state.copy(
            phase = MirrorPhase.SYNCED,
            nextUrl = null,
            lastSyncedAt = now,
            consecutiveFailures = 0,
            lastError = null,
            updatedAt = now,
        )
        val justCompletedFirstSync =
            state.firstCompletedAt == 0L && mode == MirrorMode.BACKFILL && reason == FinishReason.REACHED_END
        if (justCompletedFirstSync) {
            next = next.copy(firstCompletedAt = now)
        }

        // 只有**走到底**的全量才有资格删失联行：半路收工（增量、预算用尽）时
        // 「本轮没见过」根本不代表「服务端没有了」，照删会把镜像削掉一大块。
        val isCompleteSweep = reason == FinishReason.REACHED_END &&
            (mode == MirrorMode.SWEEP || mode == MirrorMode.BACKFILL)
        if (isCompleteSweep) {
            val removed = dao.deleteStaleRows(shelf.key, state.generation)
            val orphanTags = if (removed > 0) dao.deleteOrphanTags(shelf.key) else 0
            next = next.copy(lastFullSweepAt = now)
            if (removed > 0) {
                Timber.tag(TAG).i(
                    "[%s] 全量收尾：清掉 %d 行已在别处取消的收藏（连带 %d 条标签）",
                    shelf.label, removed, orphanTags,
                )
            }
        }
        dao.upsertState(next)
        // 一轮刚走完，表头一定是最新的 —— 把「该做一次增量」的标记消费掉。
        // 不清的话：ensureShelf 在回填期间打的标记会一直留着，回填一完成，下一个 tick
        // 立刻又去走一遍表头（刚走过的那两页），白白多打两次请求。
        maintenanceRequested -= shelf.key

        val rows = dao.countOf(shelf.key)
        Timber.tag(TAG).i(
            "[%s] %s 结束（%s）：%d 页 / %d 条 / 新增 %d 条，库内共 %d 行%s",
            shelf.label, mode, reason, run.pagesDone, run.itemsSeen, run.newItems, rows,
            if (justCompletedFirstSync) "，首次全量完成 ✅" else "",
        )

        // 翻到最后一页、整份镜像第一次补齐 —— 这一刻起「倒序 / 按标签筛 / 全文搜」才真的可用。
        // 用户此前对这件事是完全无感的（整个过程刻意静默），所以给一次、且**只给一次**引导。
        if (justCompletedFirstSync) {
            BookmarkMirrorReadyBanner.announce(appContext, shelf, rows)
        }
    }

    /**
     * 一页失败了怎么办。分三类，因为处置完全不同：
     *
     * - **429**：唯一确定的频控信号。整个引擎（不只这个书架）进冷却，并且**永久放大**
     *   本进程的每页间隔——第 1 条铁律要求宁可慢也不能再撞。
     * - **5xx / 网络 IO**：服务端或链路的临时问题，指数退避重试同一个游标。
     * - **其它 4xx / 解析失败**：重试也没用，记下错误、把这一轮停掉，等下次唤醒再说。
     */
    private fun onPageFailed(
        shelf: BookmarkShelf,
        state: BookmarkMirrorStateEntity,
        error: Throwable,
    ): Tick {
        val now = System.currentTimeMillis()
        val failures = state.consecutiveFailures + 1
        val httpCode = (error as? HttpException)?.code()

        if (httpCode == 429) {
            val retryAfter = (error as? HttpException)?.retryAfterMs()
            val backoff = rateLimitCooldownMs(failures, retryAfter)
            intervalMultiplier = min(intervalMultiplier * RATE_LIMIT_SLOWDOWN, MAX_INTERVAL_MULTIPLIER)
            Timber.tag(TAG).w(
                "⚠️ 撞上 pixiv 频控(429) shelf=%s：冷却 %ds，之后每页间隔放大到 %.1fx(%dms)%s",
                shelf.label, backoff / 1000, intervalMultiplier,
                (PAGE_INTERVAL_MS * intervalMultiplier).toLong(),
                retryAfter?.let { "（服务端 Retry-After=${it / 1000}s）" } ?: "",
            )
            dao.upsertState(
                state.copy(
                    cooldownUntil = now + backoff,
                    consecutiveFailures = failures,
                    lastError = "429 rate limited",
                    lastErrorAt = now,
                    updatedAt = now,
                )
            )
            return Tick.Wait(min(backoff, MAX_SINGLE_WAIT_MS))
        }

        val retryable = error is IOException || (httpCode != null && httpCode in 500..599) || httpCode == 408
        if (retryable && failures <= MAX_CONSECUTIVE_FAILURES) {
            val backoff = transientBackoffMs(failures)
            Timber.tag(TAG).w(
                error, "[%s] 第 %d 次失败（可重试），%ds 后重试同一页", shelf.label, failures, backoff / 1000,
            )
            dao.upsertState(
                state.copy(
                    consecutiveFailures = failures,
                    lastError = error.describe(),
                    lastErrorAt = now,
                    updatedAt = now,
                )
            )
            return Tick.Wait(min(backoff, MAX_SINGLE_WAIT_MS))
        }

        Timber.tag(TAG).e(
            error, "[%s] 第 %d 次失败（不再重试本轮），游标保留在断点上等下次唤醒",
            shelf.label, failures,
        )
        // 增量维护的「该做一次」标记在开跑时就被消费掉了。这一轮失败就把它放回去，
        // 否则一次网络抖动会让这个书架白等到 6 小时后的例行窗口。
        if (activeRun?.mode == MirrorMode.MAINTAIN) maintenanceRequested += shelf.key
        dao.upsertState(
            state.copy(
                consecutiveFailures = failures,
                lastError = error.describe(),
                lastErrorAt = now,
                updatedAt = now,
            )
        )
        // 断点游标已经落盘，下次唤醒（冷启 / 用户再打开收藏页）从这里接着走，不从头。
        activeRun = null
        _activeShelfKey.value = null
        return Tick.Wait(PARKED_RETRY_MS)
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 同一 uid + 内容类型下的公开/悄悄两个书架键。 */
    private fun shelfKeysOf(uid: Long, contentType: MirrorContentType): List<String> =
        MirrorRestrict.entries.map { BookmarkShelf(uid, contentType, it).key }

    private fun newState(shelf: BookmarkShelf, now: Long) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key,
        ownerUid = shelf.ownerUid,
        contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code,
        phase = MirrorPhase.NEVER,
        nextUrl = null,
        generation = 0,
        nextBackfillSeq = 0L,
        headSeqCursor = 0L,
        headBlockCeiling = 0L,
        pagesThisRun = 0,
        itemsThisRun = 0,
        firstCompletedAt = 0L,
        lastSyncedAt = 0L,
        lastFullSweepAt = 0L,
        lastErrorAt = 0L,
        lastError = null,
        consecutiveFailures = 0,
        cooldownUntil = 0L,
        updatedAt = now,
    )

    /** 给本地新增开一个号段（用它的顶做序号），保证它高于此前的一切。 */
    private fun openHeadBlock(state: BookmarkMirrorStateEntity, now: Long): OpenedBlock {
        val top = state.headBlockCeiling + HEAD_SEQ_BLOCK
        dao.upsertState(state.copy(headBlockCeiling = top, headSeqCursor = top, updatedAt = now))
        return OpenedBlock(top)
    }

    /**
     * 发请求前把距上一次请求的间隔补满。这是限速**唯一**的执行点：
     * 首屏、翻页、换书架、冷却结束后的第一发，全都要过这里。
     */
    private suspend fun awaitRateLimitWindow(shelf: BookmarkShelf) {
        val gap = System.currentTimeMillis() - lastRequestFinishedAt
        val required = nextPageDelayMs()
        if (lastRequestFinishedAt > 0L && gap < required) {
            val wait = required - gap
            Timber.tag(TAG).v("[%s] 限速：距上次请求 %dms，再等 %dms", shelf.label, gap, wait)
            delay(wait)
        }
    }

    /** 每页之间的等待：基准 × 频控放大系数 × 随机抖动。 */
    private fun nextPageDelayMs(): Long {
        val base = PAGE_INTERVAL_MS * intervalMultiplier
        val jitter = base * JITTER_RATIO
        // 抖动：多台设备/多次冷启不要卡在同一个节拍上一起打过去
        return (base + Random.nextDouble(-jitter, jitter)).toLong().coerceAtLeast(1_000L)
    }

    private fun rateLimitCooldownMs(failures: Int, retryAfterMs: Long?): Long {
        val stepped = RATE_LIMIT_COOLDOWNS[min(failures - 1, RATE_LIMIT_COOLDOWNS.lastIndex)]
        // 服务端说的话优先，但采信有上限：一个离谱的 Retry-After 能把镜像冻到进程结束。
        val serverAsked = (retryAfterMs ?: 0L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        return maxOf(stepped, serverAsked)
    }

    private fun transientBackoffMs(failures: Int): Long =
        min(TRANSIENT_BASE_BACKOFF_MS shl min(failures - 1, 5), MAX_TRANSIENT_BACKOFF_MS)

    private fun isFeatureEnabled(): Boolean = Shaft.sSettings?.isBookmarkMirrorEnabled ?: false

    private fun isOnline(): Boolean =
        appContext.appServices().networkStateManager.networkState.value?.isOnline == true

    private fun abbreviate(url: String?): String = when {
        url == null -> "首页"
        url.length <= 60 -> url
        else -> url.takeLast(48)
    }

    private fun Throwable.describe(): String =
        "${javaClass.simpleName}: ${message?.take(160).orEmpty()}"

    private fun HttpException.retryAfterMs(): Long? =
        response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L)

    // ─────────────────────────── 内部类型 ───────────────────────────

    private sealed interface Tick {
        /** 刚翻完一页，按限速间隔歇一下。 */
        data object Fetched : Tick
        /** 没活干，睡到被唤醒。 */
        data object Idle : Tick
        /** 冷却/退避，睡指定时长（也可被唤醒提前打断——冷却会在下一次 tick 里重新判定）。 */
        data class Wait(val ms: Long) : Tick
    }

    private enum class MirrorMode { BACKFILL, MAINTAIN, SWEEP }

    private enum class FinishReason { REACHED_END, KNOWN_STREAK, PAGE_BUDGET }

    private class MirrorJob(
        val shelf: BookmarkShelf,
        val state: BookmarkMirrorStateEntity,
        val mode: MirrorMode,
    )

    private class RunStart(val state: BookmarkMirrorStateEntity, val cursor: String?)

    private class OpenedBlock(val blockTop: Long)

    private class PageWriteResult(val newCount: Int, val headCursor: Long, val backfillSeq: Long)

    /** 一轮（回填/增量/重扫）的进行时状态。只有引擎线程碰它。 */
    private class MirrorRun(
        val shelf: BookmarkShelf,
        val mode: MirrorMode,
        /** 开跑时那一轮的代号。书架被重建（代号清零）后靠它立刻认出这一轮已经作废。 */
        val generation: Int,
        var cursor: String?,
        var headCursor: Long,
        var backfillSeq: Long,
    ) {
        var pagesDone: Int = 0
        var itemsSeen: Int = 0
        var newItems: Int = 0
        var knownStreak: Int = 0
    }

    companion object {
        private const val TAG = "BookmarkMirror"

        /**
         * 每页之间的基准间隔。5 秒 = 12 次/分，约为 pixiv 读接口配额（~120 次/分/IP）的
         * 十分之一 —— 即使用户同时在正常刷 app，两边加起来也离限流线很远。
         */
        const val PAGE_INTERVAL_MS: Long = 5_000L

        /** 间隔抖动比例，±15%。 */
        private const val JITTER_RATIO = 0.15

        /** 撞过 429 之后每次把间隔乘上这个数，最多放大到 [MAX_INTERVAL_MULTIPLIER]。 */
        private const val RATE_LIMIT_SLOWDOWN = 1.6
        private const val MAX_INTERVAL_MULTIPLIER = 4.0

        /** 429 的阶梯冷却。 */
        private val RATE_LIMIT_COOLDOWNS = longArrayOf(
            2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L,
        )
        private const val MAX_RETRY_AFTER_MS = 30 * 60_000L

        /** 网络/5xx 的指数退避。 */
        private const val TRANSIENT_BASE_BACKOFF_MS = 30_000L
        private const val MAX_TRANSIENT_BACKOFF_MS = 15 * 60_000L
        private const val MAX_CONSECUTIVE_FAILURES = 8

        /** 一次 `delay` 最长睡这么久，睡醒重新判定（免得抱着一个很长的睡眠错过唤醒语义）。 */
        private const val MAX_SINGLE_WAIT_MS = 10 * 60_000L

        /** 本轮被判定为「不再重试」后，等这么久再自己试一次。 */
        private const val PARKED_RETRY_MS = 30 * 60_000L

        /** 意料之外的异常后的退避。 */
        private const val UNEXPECTED_ERROR_BACKOFF_MS = 60_000L

        /** 空闲时的兜底心跳（正常靠 [kick] 唤醒，这个只防信号丢失）。 */
        private const val IDLE_POLL_MS = 15 * 60_000L

        /** 例行增量的最小间隔。 */
        private const val MAINTENANCE_INTERVAL_MS = 6 * 60 * 60_000L

        /**
         * 「用户在看这个书架」这个信号的节流窗口。窗口内重复的 [ensureShelf] 不再排增量：
         * 页面上那个公开/悄悄切换来回点几下就是几次调用，而两分钟里收藏不会有什么新变化。
         */
        private const val MIN_MAINTENANCE_GAP_MS = 2 * 60_000L

        /**
         * 全量重扫的间隔。它是**唯一**能发现「在网页端/别的设备取消了收藏」的机制，
         * 但也是最贵的（要把整个列表再翻一遍），所以定得很稀 —— 在 app 内做的取消
         * 由 [onUnbookmarked] 即时处理，根本等不到重扫。
         */
        private const val FULL_SWEEP_INTERVAL_MS = 14L * 24 * 60 * 60_000L

        /** 增量维护：连着见到这么多条已知条目就收工。两页的量，足够穿过一次翻页错位。 */
        private const val KNOWN_STREAK_STOP = 60

        /** 增量维护的页数硬上限，防止它退化成一次全量。 */
        private const val MAINTAIN_MAX_PAGES = 20

        /**
         * 新收藏序号的号段大小。一轮（或一次本地收藏）占一段，段内从顶往下发号，
         * 于是「先遇到的更新 → 号更大」和「后一轮整体高于前一轮」同时成立。
         * 一百万一段、Long 的量程，够用 9 万亿轮。
         */
        private const val HEAD_SEQ_BLOCK = 1_000_000L
    }
}
