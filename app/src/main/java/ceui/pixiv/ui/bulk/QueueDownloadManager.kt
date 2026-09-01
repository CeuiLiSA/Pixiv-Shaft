package ceui.pixiv.ui.bulk

import android.app.Application
import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.IllustDownload
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.DownloadLimitTypeUtil
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.db.queue.WorkType
import ceui.pixiv.download.StageStore
import ceui.pixiv.download.IllustCaptionExporter
import ceui.pixiv.download.maintenance.MediaStoreOrphanCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 批量下载持久化队列消费者 —— 页级并发流水线。
 *
 * # 设计要点（对比旧版"illust 串行 + awaitIllustSettled"）
 *
 *   旧：拿一条 download_queue 行 → 把这个 illust 全部 P 一股脑 [Manager.addTask]
 *       → suspend 等所有 P 从 Manager.content 消失 → 标 SUCCESS → 取下一条。
 *       缺点：多 P illust 会让 [Manager.content] 同时塞 N 条（N >> 并发数），
 *       "下载中" tab 可视行数远超用户配置的并发数；多 illust 永远不会同框。
 *
 *   新：事件驱动主循环，按页粒度补槽。同时刻可以有多条 illust 在飞，
 *       [Manager.content] 里的"活跃 P 数（INIT+DOWNLOADING、未暂停）"严格不超过
 *       [Shaft.sSettings.getMaxConcurrentDownloads]。
 *
 * # 不变量
 *
 *   1. 任何时刻 Manager.content 内 (state=INIT || state=DOWNLOADING) && !paused
 *      的元素数 ≤ maxConcurrentDownloads（[fillSlots] 唯一负责加 P，加之前查 footprint）
 *   2. 一条 download_queue 行最多对应一个 [InFlightIllust]；inflight 在 settle 时移除
 *   3. illust 全部 P 都 settle（成功被 remove / 失败留下 FAILED）后才标 SUCCESS / FAILED
 *   4. canDownloadNow=false 时 consumer 只睡，不 pull / addTask / 不标 DOWNLOADING
 *   5. cold start: [DownloadQueueDao.resurrectInProgress] 把残留 DOWNLOADING 复位 PENDING；
 *      Manager.restore 带回的 P 会在 fillSlots 拉它对应行时走"retry path"被认领回来
 *
 * # 主循环结构
 *
 *   ```
 *   ManagerReactive.contentFlow  ─┐
 *   tickle channel                ─┤── ticker (CONFLATED) ──→ withTimeoutOrNull(POLL_INTERVAL)
 *                                  │
 *                                  ▼
 *   while (!paused && canDownload) {
 *     settleCompleted()    // inflight 全部 P 都没了 → 标 SUCCESS / FAILED
 *     fillSlots()           // 按 maxConc 补 P：先补现有 inflight 剩余 P，再 pull 新行
 *     Manager.startAll()   // 触发 pumpAvailableSlots
 *     ticker.receive(timeout = POLL_INTERVAL)  // 等下一次事件或兜底超时
 *   }
 *   ```
 */
class QueueDownloadManager(app: Context) {

    /** 由 [ceui.lisa.activities.Shaft] 构造并经 ServicesProvider 暴露；构造只存 context，不碰 DB。 */
    private val appContext: Context = app.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 外部触发的"队列内容变了，再看一眼"信号；和 ManagerReactive 共同喂给主循环 */
    private val tickle = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null
    @Volatile private var paused: Boolean = false

    /**
     * Reactive 暂停状态：UI 端 collect 这个 StateFlow 让"暂停 / 继续"按钮文案
     * 自动跟随真实状态；任何 tab 调 pause() / resume() 都会即时同步。
     */
    private val _pausedFlow = MutableStateFlow(false)
    val pausedFlow: StateFlow<Boolean> get() = _pausedFlow

    /**
     * 队列脏标记 SharedFlow。任何会改变 download_queue 表内容的操作
     * （consumer 的 dao.updateStatus / LegacyBatchEnqueue 的 appendBatch /
     * 用户手动 deleteAll）都 tryEmit(Unit)，UI 端 collect 后用 suspend 的
     * dao.pageActive 拿最新行。
     *
     * 为什么不直接用 Room 的 `Flow<List<...>>`：实测 Room InvalidationTracker
     * 在 ~10 次/秒的连续 UPDATE 序列下**不可靠** —— 首次 emit 之后静默不再
     * 重新 emit，导致 queue tab list 卡在初始状态。这个 SharedFlow 是
     * belt-and-suspenders 兜底，不依赖 Room InvalidationTracker。
     *
     * replay=1 + DROP_OLDEST：新 collector 立刻拿一帧；高频 tick 自动合并。
     * 初始 tryEmit 让首个 collector 不用等 mutation 就有数据。
     */
    val queueListInvalidations: MutableSharedFlow<Unit> = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    /**
     * 懒加载 dao —— 必须延后到 [loopJob] 协程（IO 线程）首次使用时才触发 DB 打开 + migration，
     * 否则 [start] 会被 [Shaft.onCreate] 在 Main 线程上拖住（v33 首次升级时 ~数百 ms）。
     */
    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(appContext).downloadQueueDao()
    }

    /**
     * 当前在飞的 illust 跟踪表。键 = download_queue.id，按 seq 顺序插入
     * （[LinkedHashMap] 保留迭代顺序）。
     *
     * 多 illust 同时在飞时，[fillSlots] 优先把"当前 inflight 中下一页未加"的 P 喂给
     * Manager；都加完了才拉 [DownloadQueueDao.nextByStatusExcluding] 的下一条 PENDING。
     *
     * 该 map 只在 [loopJob] 单一协程内读写，不需要同步。
     */
    private val inFlight: LinkedHashMap<Long, InFlightIllust> = LinkedHashMap()

    /**
     * 断点续传进度看门狗的跨尝试状态，键 = download_queue.id。inFlight 每次 pull 都会
     * 重建，装不下「历史最好进度」；这个 map 独立于 inFlight 存活，直到该行 SUCCESS /
     * 终态 FAILED 才移除。只在 [loopJob] 单协程内读写，无需同步。
     */
    private val retryStates: HashMap<Long, RetryState> = HashMap()

    private class RetryState(
        /** 历史见过的最好 [progressScore]。 */
        var bestScore: Long = 0L,
        /** 总失败尝试次数（永不清零，用于 [ABSOLUTE_MAX_RETRY] 硬顶）。 */
        var attempts: Int = 0,
    )

    /**
     * 一条 illust 的当前进度评分：已完成 page 数 * [PAGE_SCORE_UNIT] + 在飞 page 的已下
     * 字节和。单调代理「往前走了没」——某页下完被移出 content 会让 page 项 +1（远大于
     * 字节项），单页 illust 的续传前沿推进则体现在字节项。用于判断该不该清零重试计数。
     */
    private fun progressScore(inf: InFlightIllust, remaining: List<DownloadItem>): Long {
        val completed = (inf.totalPages - remaining.size).coerceAtLeast(0)
        var bytes = 0L
        for (p in remaining) bytes += p.currentSize.coerceAtLeast(0)
        return completed * PAGE_SCORE_UNIT + bytes
    }

    /**
     * Ugoira 任务专用 scope。independent of [scope] 的主循环 —— ugoira 一条要
     * 几秒到几十秒（zip 下载 + 解压 + AnimatedGifEncoder 编码），不能压在
     * loopJob 单协程里阻塞 illust 并发。
     */
    private val ugoiraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ugoira encode 步骤的串行许可。zip 下载 / 解压都是 IO-bound，让它们 maxConc
     * 个同时跑没问题；只有 ENCODE 一步会把整段 PNG 序列 decode 成 Bitmap +
     * AnimatedGifEncoder 全帧驻留，并行多了 OOM。所以只串行 encode 这一步，下载
     * 解压并发 —— pipeline 上能同时看到 maxConc 条 ugoira。
     */
    private val ugoiraEncodeSem = Semaphore(1)

    /**
     * 已派给 [ugoiraScope] 但还没收尾的 row.id 集合。
     *
     *  - [dispatchUgoira] add，worker finally remove
     *  - [fillSlots] 拉行时把这部分加进 nextByStatusExcluding 的 excludeIds，
     *    避免主循环把同一条 ugoira 反复 launch
     *  - synchronizedSet：worker 在 [ugoiraScope] 多线程 IO 上写，主循环在
     *    [scope] 上读，需要互斥
     */
    private val ugoiraInFlightRowIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * 本轮批量的收口计数（issue #950）：队列产生的 page 不逐条弹 Toast（DownloadItem.silent），
     * 改为整批跑空时弹一条「成功 x / 失败 y」汇总。illust 在主循环单协程记账，ugoira 在
     * [ugoiraScope] 多线程记账，所以用 Atomic。跑空弹完即清零，下一批重新起算。
     */
    private val batchSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val batchFailedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 队列真正跑空（无 PENDING、无 illust/ugoira inflight）时弹汇总 Toast 并清零计数。
     * 不受 isToastDownloadResult 开关控制：那个开关灭的是「逐张刷屏」，整批一条的
     * 汇总正是 #950 要的完成反馈，关掉开关的用户也需要知道批量下载结束了。
     */
    private fun maybeToastBatchSummary() {
        val success = batchSuccessCount.getAndSet(0)
        val failed = batchFailedCount.getAndSet(0)
        if (success == 0 && failed == 0) return
        Common.showToast(appContext.getString(R.string.bulk_download_summary, success + failed, success, failed))
    }

    // [UgoiraPhase] / [UgoiraInFlight] 已移到 UgoiraTypes.kt（同包，引用方式不变：直接写名字）

    /**
     * 当前在 [ugoiraScope] 里的 ugoira 任务详情。
     * [ActiveListV3Fragment] 把这条 flow 跟 [ManagerReactive.contentFlow] combine 起来，
     * 让 ugoira 跟 illust 并排出现在"正在下载" tab，UX 跟普通图片下载一致。
     *
     * 内部用 LinkedHashMap 保持入队顺序（先进先排前面），加 synchronized 包裹。
     */
    private val ugoiraInFlightDetails = LinkedHashMap<Long, UgoiraInFlight>()
    private val _ugoiraInFlightFlow = MutableStateFlow<List<UgoiraInFlight>>(emptyList())
    val ugoiraInFlightFlow: StateFlow<List<UgoiraInFlight>> get() = _ugoiraInFlightFlow

    private fun setUgoiraPhase(rowId: Long, bean: Illust, phase: UgoiraPhase) {
        synchronized(ugoiraInFlightDetails) {
            ugoiraInFlightDetails[rowId] = UgoiraInFlight(rowId, bean, phase)
            _ugoiraInFlightFlow.value = ugoiraInFlightDetails.values.toList()
        }
    }
    private fun clearUgoiraDetail(rowId: Long) {
        synchronized(ugoiraInFlightDetails) {
            ugoiraInFlightDetails.remove(rowId)
            _ugoiraInFlightFlow.value = ugoiraInFlightDetails.values.toList()
        }
    }

    private data class InFlightIllust(
        val queueRowId: Long,
        val illustId: Long,
        val bean: Illust,
        val totalPages: Int,
        /** 已经被 [Manager.addTask] 喂出去的页数；下一次要 add 的页索引 = nextPageToAdd */
        var nextPageToAdd: Int,
        /** 入队时 download_queue.retryCount 的快照；finalize 时决定是 bumpRetry 还是 FAILED */
        val retryCountAtPull: Int,
        /** 停滞检测：上一轮 settle 看到的 (uuid:state:nonius) 拼接签名 */
        var lastSignature: String = "",
        /** 上一次 signature 发生变化的墙钟时间 */
        var lastChangeAt: Long = System.currentTimeMillis(),
    )

    /**
     * 启动冷启动恢复 + 消费循环。幂等。[Shaft.onCreate] 同步调（不能放进延迟批，
     * 见那边注释：promptResumeOnFirstActivity 要赶在首个 Activity 的 onResume 之前注册）。
     */
    fun start() {
        if (loopJob != null) return
        // 注意：这里不要触碰 dao；首次 dao 访问发生在下面的 launch 协程（IO 线程）里。
        loopJob = scope.launch {
            // 冷启动：上次崩溃残留的 DOWNLOADING 全部归位为 PENDING
            runCatching { dao.resurrectInProgress() }
                .onFailure { Timber.tag(TAG).e(it, "resurrectInProgress failed") }

            // issue #857：清理上一次会话遗留的 IS_PENDING=1 行（4.6.0~4.6.4 版本
            // 在网络抖动时下载失败留下的 0 字节 `.pending-NNNN` 文件）。这一时刻
            // 没有任何下载在进行，所以查到的全是孤儿，删除安全。
            runCatching {
                MediaStoreOrphanCleaner.cleanupPendingOrphans(appContext)
            }.onFailure { Timber.tag(TAG).w(it, "cleanupPendingOrphans failed") }

            // 断点续传的 stage 落点（cacheDir/staging_dl/*.part[.meta]）回收：永久失败 /
            // 清空队列 / 崩溃残留的孤儿 .part 会一直占 cacheDir。正在下载的 .part 会被高频
            // write 顶新 lastModified，只有真被遗弃的才老到被扫走，纯按年龄回收即安全。
            runCatching {
                val stageDir = java.io.File(appContext.cacheDir, StageStore.STAGE_DIR_NAME)
                val swept = StageStore.sweepOrphans(stageDir, STAGE_MAX_AGE_MS, System.currentTimeMillis())
                if (swept > 0) Timber.tag(TAG).i("[QUEUE-CONSUMER] swept $swept orphan stage files")
            }.onFailure { Timber.tag(TAG).w(it, "stage sweep failed") }

            // 检查是否有需要恢复的批量下载 —— 不无脑继续，让用户决定
            val pending = runCatching { dao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0)
            if (pending > 0) {
                paused = true   // 默认暂停；等用户在第一个 Activity 弹窗里决定
                _pausedFlow.value = true
                (appContext as? Application)?.let { app ->
                    promptResumeOnFirstActivity(app, pending) { resume() }
                }
                Timber.tag(TAG).i("cold start: $pending pending items, awaiting user decision")
            } else {
                paused = false
                _pausedFlow.value = false
            }

            // 把 ManagerReactive.contentFlow 的 emit 桥到 ticker，让主循环既能响应
            // 队列变更（tickle）也能响应 Manager 内部状态变更（addTask / state 翻转 /
            // P 完成 remove / clearAll / pumpAvailableSlots / progress）。
            launch {
                ManagerReactive.contentFlow.collect { tickle.trySend(Unit) }
            }

            runMainLoop()
        }
        Timber.tag(TAG).d("QueueDownloadManager started")
    }

    /**
     * 主循环。在 IO 线程协程内运行。
     */
    private suspend fun runMainLoop() {
        Timber.tag(TAG).i("[QUEUE-CONSUMER] main loop start")
        while (true) {
            if (paused) {
                tickle.receive()  // 阻塞直到 resume() 触发 tickle
                continue
            }
            if (!DownloadLimitTypeUtil.canDownloadNow()) {
                Timber.tag(TAG).i("[QUEUE-CONSUMER] canDownloadNow=false, holding")
                withTimeoutOrNull(NETWORK_GATE_SLEEP_MS) { tickle.receive() }
                continue
            }

            try {
                settleCompleted()
                val didAdd = fillSlots()
                // 只在真加了 P 时才 trigger pump —— ManagerReactive.contentFlow 桥到
                // tickle 后，每个 progress 1% 都会唤醒主循环；空跑也调一遍就是每秒做
                // 几百次"全 content 扫描 + pump"，纯 CPU 浪费。
                //
                // triggerPump() 而不是 startAll()：startAll 会对 content 每条跑
                // resurrectIfStranded，对刚 setState(DOWNLOADING) 但 handles.put 还在
                // Schedulers.io→mainThread 异步队列里的 in-flight item 误判 stranded
                // → 翻 INIT + nonius=0 → pump 又再 dispatch 一次（双 chain 抢同
                // stage / UI 进度闪烁回弹）。
                if (didAdd) {
                    runCatching { Manager.get().triggerPump() }
                        .onFailure { Timber.tag(TAG).w(it, "[QUEUE-CONSUMER] triggerPump failed") }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                // 主循环最外层兜底：单轮异常不能让 consumer 死掉
                Timber.tag(TAG).e(t, "[QUEUE-CONSUMER] tick threw")
            }

            // inflight 空且没有 PENDING 了：纯空闲态，停下来等下一次 tickle（不再忙等）
            if (inFlight.isEmpty()) {
                // 进真正的"空闲睡眠"前再修一次 DB —— dao.updateStatus 用 runCatching
                // 包着，某次抛了被吞会让 DB 里残留"幽灵 DOWNLOADING"行（inflight 已经
                // remove，下次 nextByStatusExcluding 也不会拿它）。此刻 inflight 为空，
                // 任何 DB 里的 DOWNLOADING 都是脏数据，统一拉回 PENDING 安全。
                runCatching { dao.resurrectInProgress() }
                    .onFailure { Timber.tag(TAG).w(it, "[QUEUE-CONSUMER] idle resurrect failed") }
                val hasPending = runCatching { dao.countByStatus(QueueStatus.PENDING) > 0 }
                    .getOrDefault(false)
                if (!hasPending) {
                    // ugoira 行在 DB 里是 DOWNLOADING、也不在 inFlight，得单独确认没在飞
                    if (ugoiraInFlightRowIds.isEmpty()) {
                        maybeToastBatchSummary()
                    }
                    Timber.tag(TAG).i("[QUEUE-CONSUMER] idle, awaiting next tickle")
                    tickle.receive()
                    continue
                }
            }

            // 有 inflight 在跑：等下一个事件（content 变化 / tickle）或 polling 兜底
            withTimeoutOrNull(POLL_INTERVAL_MS) { tickle.receive() }
        }
    }

    /**
     * 扫一遍 [inFlight] 看哪些 illust 已经全部 P 都 settle 了：
     *   - 全部 page 已加进 Manager.content 过（[InFlightIllust.nextPageToAdd] == totalPages）
     *   - 当前 Manager.content 内对应该 illust 的 page 全是 FAILED（其他状态都 settle 完）
     *
     * 然后据此标 SUCCESS / FAILED / 触发重试。同时做停滞检测，避免下载内核卡死时
     * 永远不出来。
     */
    private suspend fun settleCompleted() {
        if (inFlight.isEmpty()) return

        val snapshot = snapshotManagerContent()
        val byIllust: Map<Long, List<DownloadItem>> = snapshot.groupBy { it.illust?.id ?: -1L }

        val now = System.currentTimeMillis()
        val toFinalize = mutableListOf<Pair<InFlightIllust, FinalizeKind>>()

        val iter = inFlight.entries.iterator()
        while (iter.hasNext()) {
            val inf = iter.next().value
            val pages = byIllust[inf.illustId] ?: emptyList()

            // 停滞检测：以 (uuid:state:nonius) 列表为签名
            val signature = pages.joinToString("|") { it.uuid + ":" + it.state + ":" + it.nonius }
            if (signature != inf.lastSignature) {
                inf.lastSignature = signature
                inf.lastChangeAt = now
            } else if (now - inf.lastChangeAt > STALL_TIMEOUT_MS) {
                Timber.tag(TAG).w(
                    "[QUEUE-CONSUMER] stall detected illust=${inf.illustId} " +
                            "remaining=${pages.size} totalPages=${inf.totalPages} → finalize as failed"
                )
                toFinalize += inf to FinalizeKind.STALLED
                iter.remove()
                continue
            }

            // 还没把所有 P 都 add 出去 —— 后面 fillSlots 会继续推进，这里先跳过
            if (inf.nextPageToAdd < inf.totalPages) continue

            if (pages.isEmpty()) {
                // 所有 P 都成功被 remove 了
                toFinalize += inf to FinalizeKind.SUCCESS
                iter.remove()
                continue
            }

            // 只要还有 INIT / DOWNLOADING / PAUSED 在 → 还没 settle
            val unsettled = pages.count {
                it.state == DownloadItem.DownloadState.INIT
                        || it.state == DownloadItem.DownloadState.DOWNLOADING
                        || it.isPaused
                        || it.state == DownloadItem.DownloadState.PAUSED
            }
            if (unsettled > 0) continue

            // 剩下的应该都是 FAILED：illust 视为失败
            toFinalize += inf to FinalizeKind.FAILED
            iter.remove()
        }

        if (toFinalize.isEmpty()) return

        for ((inf, kind) in toFinalize) {
            // 复用顶部的 snapshot：每个 illust 的 page 集合互不影响，clearOne illust A
            // 不会改 illust B 的 byIllust[B.id] 视图。省掉每个 finalize 分支再各自
            // snapshotManagerContent() 的同步开销。
            val remainingForThis = byIllust[inf.illustId] ?: emptyList()
            when (kind) {
                FinalizeKind.SUCCESS -> {
                    retryStates.remove(inf.queueRowId)  // 进度看门狗状态随行终结
                    val updated = runCatching {
                        dao.updateStatus(
                            inf.queueRowId, QueueStatus.SUCCESS,
                            finishedAt = System.currentTimeMillis()
                        )
                    }.onFailure {
                        // 关键 DB 失败必须 ERROR 级 —— 这条行会卡 DOWNLOADING，幸好
                        // [runMainLoop] idle 路径里有 resurrectInProgress 兜底。
                        Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark SUCCESS failed id=${inf.queueRowId}")
                    }.getOrDefault(0)
                    // 只有行还真实存在（没被「清空全部」删掉）才计入批量汇总，
                    // 否则清空后队列跑空会弹一条幽灵「批量下载完成」。
                    if (updated > 0) batchSuccessCount.incrementAndGet()
                    queueListInvalidations.tryEmit(Unit)
                    Timber.tag(TAG).i(
                        "[QUEUE-CONSUMER] illust=${inf.illustId} SUCCESS (db=${if (updated > 0) "ok" else "miss"})"
                    )
                }

                FinalizeKind.FAILED, FinalizeKind.STALLED -> {
                    // —— 断点续传的进度看门狗 ——
                    // 有了续传，每次尝试只把字节前沿往前推、不再从头来。所以「重试上限」的
                    // 语义从"总尝试次数"改成"连续无进展次数"：本轮若比历史最好进度更前，就
                    // 把重试计数清零，让抖动线路（十几 M 的图断了几十次）只要每次都往前挪
                    // 就能一直续下去，而不是 MAX_RETRY 次后放弃。ABSOLUTE_MAX_RETRY 是防
                    // 病态无限循环（每次只吐 1 字节又断）的硬顶。
                    val st = retryStates.getOrPut(inf.queueRowId) { RetryState() }
                    st.attempts++
                    val score = progressScore(inf, remainingForThis)
                    val madeProgress = score > st.bestScore
                    if (madeProgress) st.bestScore = score
                    val hardCapHit = st.attempts >= ABSOLUTE_MAX_RETRY
                    val canRetry = !hardCapHit && (madeProgress || inf.retryCountAtPull + 1 < MAX_RETRY)
                    if (canRetry) {
                        // 标回 PENDING —— 失败的 page 留在 Manager.content 里让下一轮 fillSlots
                        // 走 retry path（FAILED→INIT 后继续跑）。STALLED 例外：先把"卡住"的
                        // page 强制 clearOne 让下一轮干净启动。
                        if (kind == FinalizeKind.STALLED) {
                            for (p in remainingForThis) {
                                runCatching { Manager.get().clearOne(p.uuid) }
                                    .onFailure { Timber.tag(TAG).w(it, "clearOne stalled p=${p.uuid}") }
                            }
                        }
                        // 有进展 → 清零重试计数（下次从 0 起算）；无进展 → 照常 +1 逼近 MAX_RETRY。
                        if (madeProgress) {
                            runCatching { dao.resetRetry(inf.queueRowId) }
                                .onFailure { Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] resetRetry failed id=${inf.queueRowId}") }
                        } else {
                            runCatching { dao.bumpRetry(inf.queueRowId) }
                                .onFailure { Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] bumpRetry failed id=${inf.queueRowId}") }
                        }
                        runCatching {
                            dao.updateStatus(
                                inf.queueRowId, QueueStatus.PENDING,
                                err = if (kind == FinalizeKind.STALLED) "stalled" else null
                            )
                        }.onFailure {
                            Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark PENDING failed id=${inf.queueRowId}")
                        }
                        queueListInvalidations.tryEmit(Unit)
                        Timber.tag(TAG).i(
                            "[QUEUE-CONSUMER] illust=${inf.illustId} retry kind=$kind " +
                                    "progress=$madeProgress attempts=${st.attempts} score=$score"
                        )
                    } else {
                        retryStates.remove(inf.queueRowId)
                        // 终态失败：把残留的 FAILED P 从 content 清掉，免得长期下载活动后
                        // "下载中" tab 堆一堆 FAILED 行 / Manager.startAll 又把它们翻 INIT。
                        for (p in remainingForThis) {
                            runCatching { Manager.get().clearOne(p.uuid) }
                                .onFailure { Timber.tag(TAG).w(it, "clearOne terminal-fail p=${p.uuid}") }
                        }
                        val updated = runCatching {
                            dao.updateStatus(
                                inf.queueRowId, QueueStatus.FAILED,
                                err = if (kind == FinalizeKind.STALLED) "stalled" else "all pages failed",
                                finishedAt = System.currentTimeMillis(),
                            )
                        }.onFailure {
                            Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark FAILED failed id=${inf.queueRowId}")
                        }.getOrDefault(0)
                        // 同 SUCCESS 分支：行已被清空时不计入汇总
                        if (updated > 0) batchFailedCount.incrementAndGet()
                        queueListInvalidations.tryEmit(Unit)
                        Timber.tag(TAG).w(
                            "[QUEUE-CONSUMER] illust=${inf.illustId} permanently FAILED kind=$kind"
                        )
                    }
                }
            }
        }
    }

    private enum class FinalizeKind { SUCCESS, FAILED, STALLED }

    /**
     * 按 maxConc 补 P 进 Manager.content：
     *   - 优先填现有 inflight illust 的下一未 add 页
     *   - 都填满了再 pull 下一条 PENDING（排除已在 inflight 的）
     *
     * 每轮迭代实时重算 footprint —— addTask 之后 [Manager.content] 已经同步变长，
     * 拿最新 snapshot 重算最稳妥。
     *
     * @return 本轮是否真的把至少一个 P 添进了 [Manager.content]。给主循环判断
     *         "需不需要调 startAll 触发 pump" 用，避免空转的 startAll 风暴。
     */
    private suspend fun fillSlots(): Boolean {
        val maxConcRaw = runCatching { Shaft.sSettings.getMaxConcurrentDownloads() }.getOrDefault(1)
        val maxConc = maxConcRaw.coerceIn(1, 5)

        // 防御性迭代上限。正常 budget = maxConc 次"添加"+ 至多 maxConc 次"拉行"
        // = 2*maxConc；+8 给 retry path 偶发返回 false 重拉的余量。命中说明上面
        // 的逻辑出 bug 了，超出就停 + 警告，等下一 tick。
        var didAdd = false
        var safety = maxConc * 2 + 8

        while (safety-- > 0) {
            if (!DownloadLimitTypeUtil.canDownloadNow() || paused) return didAdd

            val snapshot = snapshotManagerContent()
            val illustActive = snapshot.count {
                !it.isPaused && (it.state == DownloadItem.DownloadState.INIT
                        || it.state == DownloadItem.DownloadState.DOWNLOADING)
            }
            // ugoira 跟 illust 共享 maxConc 槽位 —— 用户视角"最大并行数"包含所有
            // 正在跑的活儿（illust page + ugoira），不区分类型。否则 maxConc=3 时
            // 用户实际看到 3 illust + 1 ugoira = 4 条在跑，违反"设置=上限"的直觉。
            // ugoira 内部的 encode 步骤靠 [ugoiraEncodeSem] 串行，meta/download/extract
            // 可以 maxConc 条同时跑，pipeline 满负荷。
            val ugoiraExcl = synchronized(ugoiraInFlightRowIds) {
                ArrayList(ugoiraInFlightRowIds)
            }
            val activeFootprint = illustActive + ugoiraExcl.size
            if (activeFootprint >= maxConc) return didAdd

            // 1) 现有 inflight illust 还有未 add 的 P → 添一个
            val infNeedingPage = inFlight.values.firstOrNull { it.nextPageToAdd < it.totalPages }
            if (infNeedingPage != null) {
                val i = infNeedingPage.nextPageToAdd
                val ok = runCatching {
                    val di = DownloadItem(infNeedingPage.bean, i)
                    di.url = IllustDownload.getUrl(infNeedingPage.bean, i)
                    di.showUrl = IllustDownload.getShowUrl(infNeedingPage.bean, i)
                    di.isSilent = true  // 批量 page 不逐条弹 Toast，跑空时统一弹汇总（issue #950）
                    Manager.get().addTask(di)
                }.onFailure {
                    Timber.tag(TAG).w(
                        it, "[QUEUE-CONSUMER] addTask failed illust=${infNeedingPage.illustId} page=$i"
                    )
                }.isSuccess
                infNeedingPage.nextPageToAdd = i + 1
                if (ok) didAdd = true
                continue
            }

            // 2) inflight 都满了 P，拉下一条 PENDING。
            //    excludeIds = illust inflight + ugoira inflight。
            val excludeIds = if (inFlight.isEmpty() && ugoiraExcl.isEmpty()) {
                emptyList()
            } else {
                ArrayList<Long>(inFlight.size + ugoiraExcl.size).apply {
                    addAll(inFlight.keys)
                    addAll(ugoiraExcl)
                }
            }
            val row = runCatching {
                if (excludeIds.isEmpty()) dao.nextByStatus(QueueStatus.PENDING)
                else dao.nextByStatusExcluding(QueueStatus.PENDING, excludeIds)
            }.getOrNull() ?: return didAdd  // 没有更多 PENDING 可拉

            // pullRowToInFlight 返回 false 时该行已就地 SUCCESS / FAILED / PENDING 处理；
            // 下一轮 while 自然继续尝试。retry path 进 inflight 后 footprint 会被
            // existing pages 顶起，下一轮 footprint 检查就 break。
            pullRowToInFlight(row)
        }
        if (safety <= 0) {
            Timber.tag(TAG).w("[QUEUE-CONSUMER] fillSlots safety exhausted (maxConc=$maxConc)")
        }
        return didAdd
    }

    /**
     * 把 [row] 转成 inflight 入口；对 GIF / API 解析失败 / 已在 content 的 retry path
     * 各自处理。返回 true 表示成功放入 inflight，false 表示这条已就地处理（GIF skip /
     * 错误已记账），调用方应该 continue 拉下一条。
     */
    private suspend fun pullRowToInFlight(row: DownloadQueueEntity): Boolean {
        val bean = try {
            resolveIllust(row)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "[QUEUE-CONSUMER] resolveBean failed illust=${row.illustId}")
            if (row.retryCount + 1 < MAX_RETRY) {
                runCatching { dao.bumpRetry(row.id) }
                runCatching { dao.updateStatus(row.id, QueueStatus.PENDING, err = e.message) }
                delay(SOFT_ERROR_BACKOFF_MS)
            } else {
                runCatching {
                    dao.updateStatus(
                        row.id, QueueStatus.FAILED, err = e.message,
                        finishedAt = System.currentTimeMillis()
                    )
                }
            }
            queueListInvalidations.tryEmit(Unit)
            return false
        }

        if (bean.isGif()) {
            // ugoira 走独立管线（getGifPackage → zip → 解压 → encodeGif → 写用户目录），
            // 不进 Manager.content 的页级并发模型 —— [downloadUgoira] 用单独的
            // [ugoiraScope] 串行跑，跟 illust 并发互不挤占。
            //
            // 标 DOWNLOADING 后立刻 launch 一个 background job：主循环不被阻塞，
            // 继续 pull 别的 illust。job 自己写终态（SUCCESS / FAILED / bumpRetry）。
            //
            // dispatchUgoira 同时维护 ugoiraInFlightRowIds，避免主循环把同一行重复
            // launch。row.id 必然唯一（PRIMARY KEY），用作幂等 key。
            dispatchUgoira(row, bean)
            return false
        }

        val pageCount = if (bean.page_count <= 0) 1 else bean.page_count

        // 标 DOWNLOADING（入 inflight 之前 —— 这样 nextByStatusExcluding 排除生效）
        runCatching { dao.updateStatus(row.id, QueueStatus.DOWNLOADING) }
        queueListInvalidations.tryEmit(Unit)

        // Retry path 检测：Manager.content 里残留这个 illust 的 P
        // （来源：上轮 bumpRetry → PENDING；或冷启动 Manager.restore 带回的）
        val target = row.illustId
        val existing = snapshotManagerContent().filter { it.illust?.id == target }

        val nextPageToAddInit = if (existing.isNotEmpty()) {
            // 把所有非 INIT 状态翻 INIT，让 pumpAvailableSlots 重新挑选：
            //   - FAILED：上轮真失败的 P
            //   - DOWNLOADING：**冷启动 Manager.restore 带回的 stranded 状态**——
            //     原 Disposable 已随进程消失，pump 又把 DOWNLOADING 算进 activeCount
            //     但 getFirstReady 只挑 INIT，不翻就永远占着槽位卡死整队
            //   - PAUSED：之前用户手动暂停过，现在重新接管要抹掉
            // SUCCESS 不会出现（complete 时已 content.remove）；INIT 已经 ready，跳过。
            //
            // 直接改 DownloadItem 字段不走 synchronized：跟 Manager.startAll 内部循环
            // 同源做法（state 是 volatile-style 单值，顺序不强）；只在 IO 线程做。
            for (p in existing) {
                // 真在跑的 page（handles 里有 uuid）跳过 —— 否则把状态翻 INIT 会让
                // 下一轮 pumpAvailableSlots 再 dispatch 一条 Observable，跟原 chain
                // 抢同一个 stage 文件 + targetUri（实测出过同 uuid 两次 read-start）。
                // 冷启动 stranded DOWNLOADING 的 handle 已随进程消失，不会被这条 continue 误伤。
                if (Manager.get().isRunningHandle(p.uuid)) continue
                val s = p.state
                if (s == DownloadItem.DownloadState.FAILED
                    || s == DownloadItem.DownloadState.DOWNLOADING
                    || s == DownloadItem.DownloadState.PAUSED) {
                    p.setState(DownloadItem.DownloadState.INIT)
                }
                if (p.isPaused) p.setPaused(false)
            }
            ManagerReactive.invalidate()
            // 已知 gap：existing.size 可能 < pageCount —— 比如上轮 SUCCESS 被 remove
            // 的 P 不在 content 里、但又有别的途径让其它 P 缺席（如 clearOne）。我们
            // 只能用 existing 这部分继续跑，缺的 P 不补；老 awaitIllustSettled 也是
            // 同样行为，没回归。要补齐需要按 page index 精确比对，目前不值得。
            pageCount
        } else 0

        inFlight[row.id] = InFlightIllust(
            queueRowId = row.id,
            illustId = row.illustId,
            bean = bean,
            totalPages = pageCount,
            nextPageToAdd = nextPageToAddInit,
            retryCountAtPull = row.retryCount,
        )
        Timber.tag(TAG).i(
            "[QUEUE-CONSUMER] TAKE id=${row.id} illustId=${row.illustId} " +
                    "pageCount=$pageCount existing=${existing.size} retry=${row.retryCount}"
        )
        // 只在首次拉入时导出简介：retry path（existing 非空）这条作品上一轮已经导出过，
        // 再导一次在 Rename 策略下会多出一份 `xxx (1).txt`。
        if (existing.isEmpty()) IllustCaptionExporter.export(bean)
        return true
    }

    // [promptResumeOnFirstActivity] / [showResumePrompt] 已移到 QueueColdStartPrompt.kt

    // —— 公共 API（被 Fragment / 其他 enqueue 入口调用） ——

    fun notifyNewItems() { tickle.trySend(Unit) }
    fun pause() {
        paused = true
        _pausedFlow.value = true
        // 联动：illust 走 Manager.stopAll() 立刻停 disposables；ugoira 这边等价做法
        // 是 cancel 已派出去的 worker —— 否则一条 50MB zip + ~1s 编码会跑完才理睬
        // 用户的暂停意图。worker 的 catch CancellationException 会把行翻回 PENDING，
        // resume 时主循环重新 pull。
        cancelOngoingUgoiraWorkers()
        Timber.tag(TAG).i("[QUEUE-CONSUMER] pause() called")
    }
    fun resume() {
        paused = false
        _pausedFlow.value = false
        val sent = tickle.trySend(Unit).isSuccess
        Timber.tag(TAG).i("[QUEUE-CONSUMER] resume() called, tickle.trySend=$sent")
    }
    fun isPaused(): Boolean = paused

    /**
     * 给"清空全部"用：取消所有正在跑 / 等 Semaphore 的 ugoira worker。row 已经在
     * 调用方 `dao.deleteAll()` 删掉，worker 的 cancellation cleanup 会尝试把行翻
     * PENDING 但 row 不存在，dao 静默 no-op，不会异常。
     */
    fun cancelOngoingUgoiraWorkers() {
        ugoiraScope.coroutineContext.cancelChildren()
    }

    // —— ugoira 派发 ——

    /**
     * 把 ugoira [row] 派给 [ugoiraScope] 跑。**不阻塞 [loopJob]**，主循环继续 pull
     * 别的 illust。
     *
     * 状态机（与 illust 路径独立）：
     *   - 派出去：[ugoiraInFlightRowIds] 加 row.id（fillSlots 排它），status 仍是 PENDING
     *   - worker 拿 [ugoiraSem] 后才翻 DOWNLOADING（UI 上只有真正在跑的那条显示
     *     DOWNLOADING；其余 ugoira 行排队期间是 PENDING，不会出现"100 条同时
     *     DOWNLOADING 但只有 1 条在动"的假象）
     *   - 完成：SUCCESS / 失败重试 PENDING (bumpRetry) / 终态 FAILED
     *   - finally：移除 [ugoiraInFlightRowIds]，tryEmit 列表脏标，trySend tickle
     *     让主循环立刻再 pull 下一行（不必等 POLL_INTERVAL_MS）
     */
    private fun dispatchUgoira(row: DownloadQueueEntity, bean: Illust) {
        if (!ugoiraInFlightRowIds.add(row.id)) {
            Timber.tag(TAG).w("[QUEUE-CONSUMER] ugoira already dispatched row=${row.id}")
            return
        }
        // 一进派发就 push 到 ugoiraInFlightFlow，UI 能立刻看到 QUEUED 行
        setUgoiraPhase(row.id, bean, UgoiraPhase.QUEUED)
        queueListInvalidations.tryEmit(Unit)
        Timber.tag(TAG).i("[QUEUE-CONSUMER] ugoira queued illust=${row.illustId} row=${row.id}")

        ugoiraScope.launch {
            var cancelledMidWay = false
            try {
                // 不再外层 withPermit —— ENCODE 步骤的串行已经下放到 [downloadUgoira]
                // 内部，meta/download/extract 三步可以 maxConc 条同时跑。
                runCatching { dao.updateStatus(row.id, QueueStatus.DOWNLOADING) }
                queueListInvalidations.tryEmit(Unit)
                Timber.tag(TAG).i("[QUEUE-CONSUMER] ugoira start illust=${row.illustId}")
                downloadUgoira(bean, ugoiraEncodeSem) { phase ->
                    setUgoiraPhase(row.id, bean, phase)
                }
                val updated = runCatching {
                    dao.updateStatus(
                        row.id, QueueStatus.SUCCESS,
                        finishedAt = System.currentTimeMillis()
                    )
                }.getOrDefault(0)
                // 行已被「清空全部」删掉（deleteAll 赛跑赢了 encode）时不计入批量汇总
                if (updated > 0) batchSuccessCount.incrementAndGet()
                Timber.tag(TAG).i("[QUEUE-CONSUMER] ugoira success illust=${row.illustId}")
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // pause / clearAll 路径：把行翻回 PENDING，让 resume 时主循环再 pull。
                // clearAll 已 deleteAll，updateStatus 命中不存在的 id，Room 静默 no-op。
                cancelledMidWay = true
                throw cancellation
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "[QUEUE-CONSUMER] ugoira failed illust=${row.illustId}")
                if (row.retryCount + 1 < MAX_RETRY) {
                    runCatching { dao.bumpRetry(row.id) }
                    runCatching {
                        dao.updateStatus(row.id, QueueStatus.PENDING, err = e.message)
                    }
                } else {
                    val updated = runCatching {
                        dao.updateStatus(
                            row.id, QueueStatus.FAILED, err = e.message,
                            finishedAt = System.currentTimeMillis()
                        )
                    }.getOrDefault(0)
                    if (updated > 0) batchFailedCount.incrementAndGet()
                }
            } finally {
                // finally 在已取消协程里 dao.updateStatus 会立刻抛 CancellationException
                // —— 用 NonCancellable 让收尾 dao 写真的能落库。tryEmit / trySend 不挂
                // 起，不受 cancellation 影响。
                ugoiraInFlightRowIds.remove(row.id)
                clearUgoiraDetail(row.id)
                if (cancelledMidWay) {
                    withContext(NonCancellable) {
                        runCatching { dao.updateStatus(row.id, QueueStatus.PENDING) }
                    }
                }
                queueListInvalidations.tryEmit(Unit)
                tickle.trySend(Unit)
            }
        }
    }

    // [snapshotManagerContent] / [resolveIllust] 已移到 QueueConsumerHelpers.kt

    private companion object {
        private const val TAG = "QueueDownloadManager"

        // —— 调度参数 ——
        /**
         * 「连续无进展」的最大重试次数（对应 download_queue.retryCount）。有断点续传后，
         * 只要一次尝试推进了字节前沿就会 [DownloadQueueDao.resetRetry] 清零，所以这个上限
         * 只在**完全下不动**（404 / 一直 0 字节）时才生效 —— 快速失败一条真下不了的。
         */
        private const val MAX_RETRY = 3
        /**
         * 绝对重试硬顶：防止病态场景（服务器每次只吐几字节又断，进度看门狗永远判"有进展"）
         * 把一条 illust 无限重试下去。正常抖动线路远到不了这个数就续完了。
         */
        private const val ABSOLUTE_MAX_RETRY = 50
        /**
         * 进度评分单位：完成一个 page 记 1 个单位（1TB，远超任何单文件字节数），page 内
         * 已下字节直接累加。这样「某页下完被移出 content」也稳稳算作前进（page 项占主导），
         * 单页 illust 的续传前沿推进也能被识别。见 [progressScore]。
         */
        private const val PAGE_SCORE_UNIT = 1_000_000_000_000L
        /** 孤儿 stage 文件（.part / .part.meta）的最大存活期，冷启动扫一次回收，见 [StageStore.sweepOrphans]。 */
        private const val STAGE_MAX_AGE_MS = 48L * 60 * 60 * 1000
        /** 主循环兜底 polling：即使 ticker 没动，也每隔一段时间复查一次 */
        private const val POLL_INTERVAL_MS = 800L
        /** canDownloadNow=false 时挂起的 sleep 周期 */
        private const val NETWORK_GATE_SLEEP_MS = 30_000L
        /** 一条 inflight illust 持续无任何 P 状态变化的"停滞"上限；超过则强制按失败收口 */
        private const val STALL_TIMEOUT_MS = 90_000L
        /** resolveIllust 失败 / addTask 异常等"软错误"后的退避 */
        private const val SOFT_ERROR_BACKOFF_MS = 1500L

    }
}

