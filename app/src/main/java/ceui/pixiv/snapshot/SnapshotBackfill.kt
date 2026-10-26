package ceui.pixiv.snapshot

import android.content.Context
import android.net.ConnectivityManager
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.pixiv.actionqueue.ActionEvent
import ceui.pixiv.actionqueue.ActionQueue
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.QueuePolicy
import ceui.pixiv.actionqueue.QueueState
import ceui.pixiv.actions.parsePayload
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.NetworkMonitor
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/** 补齐哪部分收藏。 */
enum class SnapshotBackfillScope(internal val restricts: List<String>) {
    ALL(listOf(Params.TYPE_PUBLIC, Params.TYPE_PRIVATE)),
    PUBLIC(listOf(Params.TYPE_PUBLIC)),
    PRIVATE(listOf(Params.TYPE_PRIVATE)),
}

/** 一次补齐的选项，对整个队列生效（issue #1275：一次选择，套用到整批）。 */
data class SnapshotBackfillConfig(
    val scope: SnapshotBackfillScope,
    val includeComments: Boolean,
    val includeOriginal: Boolean,
    val wifiOnly: Boolean,
)

/**
 * 一轮补齐的持久化记录：选项 + 计数。只存原始类型 —— 它被 gson 写进 MMKV、跨版本读回，
 * 枚举交给 [scopeName] 存名字。
 *
 * @param discovered 已读到的收藏件数（列表还没读完时会继续增长，见 [scanChains]）
 * @param queued     其中排进队列等待生成的件数
 * @param existing   因已有正式快照跳过
 * @param done       新生成或由自动快照转正
 * @param unavailable 作品已删除 / 不公开
 * @param failed     重试耗尽的件数；「重试失败」会把它们放回队列并清零
 * @param scanChains 还没读到底的收藏列表条数（公开 / 私密各一条）
 * @param failedScans 读列表终态失败的条数
 */
data class SnapshotBackfillRun(
    val runId: String,
    val owner: Long,
    val startedAt: Long,
    val scopeName: String,
    val includeComments: Boolean,
    val includeOriginal: Boolean,
    val wifiOnly: Boolean,
    val discovered: Int = 0,
    val queued: Int = 0,
    val existing: Int = 0,
    val done: Int = 0,
    val unavailable: Int = 0,
    val failed: Int = 0,
    val scanChains: Int = 0,
    val failedScans: Int = 0,
    val paused: Boolean = false,
    val pausedForStorage: Boolean = false,
    val finishedAt: Long = 0L,
) {
    /** 已经有结果的件数（含跳过与失败）；进度条与「剩余」都按它算。 */
    val settled: Int get() = existing + done + unavailable + failed

    val remaining: Int get() = (discovered - settled).coerceAtLeast(0)

    val isScanning: Boolean get() = scanChains > 0 && finishedAt == 0L
}

enum class SnapshotBackfillPhase {
    IDLE,
    RUNNING,
    PAUSED,
    PAUSED_LOW_STORAGE,
    WAITING_LOGIN,
    WAITING_NETWORK,
    WAITING_WIFI,
    COOLING_DOWN,
    FINISHED,
}

data class SnapshotBackfillState(
    val run: SnapshotBackfillRun? = null,
    val phase: SnapshotBackfillPhase = SnapshotBackfillPhase.IDLE,
    /** 正在生成的作品标题；读列表、转正和等待时为 null。 */
    val currentTitle: String? = null,
)

/**
 * 「为当前收藏补齐离线快照」（#1275）。
 *
 * 一件作品一条动作，进一条**独立**的 [ActionQueue]（单独的库，单独的暂停）：几千件收藏排在
 * 收藏 / 下载队列里会把它们堵上好几个小时，而用户按「暂停」只该停这一件事。队列本身给的是
 * 串行 + 间隔 + 429 冷却 + 进程被杀后续跑；这里补上业务那一层：
 * - 一轮的选项与计数落 MMKV（按账号分），页面关掉、进程重启都能接着显示进度；
 * - 「暂停」落盘，重启后保持暂停；剩余空间不足时由生成那一步自动暂停；
 * - 仅 Wi-Fi：闸门在计费网络上关着，切到 Wi-Fi 后最迟一个轮询周期内继续。
 *
 * 普通 class，由 [ceui.pixiv.actions.PixivActionQueue] 持有一份并在其 start 里启动 —— 与收藏、
 * 小说下载两条队列同一个宿主、同一套登录 / 联网判据。
 */
class SnapshotBackfill(app: Context) {

    private val app: Context = app.applicationContext

    private val prefs: MMKV by lazy { MMKV.mmkvWithID(PREFS_ID) }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Timber.tag(TAG).e(t, "snapshot backfill scope crashed") }
    )

    /**
     * 开始 / 停止 / 收尾判定互斥：开始一轮要先清空旧行、写新记录、再排进第一页，中间若插进一次
     * 「队列空了 → 判完成」，新的一轮还没开始就会被标成已完成。
     */
    private val control = Mutex()

    @Volatile
    private var queue: ActionQueue? = null

    private val run = MutableStateFlow<SnapshotBackfillRun?>(null)
    private val current = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(SnapshotBackfillState())

    val state: StateFlow<SnapshotBackfillState> = _state.asStateFlow()

    /** 已有正式快照的作品 id。每轮开始时从磁盘读一次，之后随生成增量维护（见 [hasFormal]）。 */
    private var formalIds: MutableSet<Long>? = null

    /**
     * [formalIds] 单独一把锁：首次加载要把正式库整个读一遍（几千个快照就是几秒），
     * 和记录写入共用 `this` 的话，主线程上点「暂停」会被它卡住。
     */
    private val formalLock = Any()

    fun start(monitor: NetworkMonitor) {
        if (queue != null) return
        val isOnline = { monitor.isConnected }
        val instance = ActionQueue.withRoomStore(
            context = app,
            handlers = mapOf(
                SnapshotBackfillTypes.SCAN to BackfillScanHandler(this, isOnline),
                SnapshotBackfillTypes.ILLUST to BackfillIllustHandler(app, this, isOnline),
            ),
            databaseName = DATABASE_NAME,
            policy = QueuePolicy(minGapMs = MIN_GAP_MS, idlePollMs = IDLE_POLL_MS),
            gate = {
                SessionManager.isLoggedIn && monitor.isConnected &&
                    !(run.value?.wifiOnly == true && isMetered())
            },
            owner = { SessionManager.loggedInUid.toString() },
            onError = { message, t -> Timber.tag(TAG).e(t, message) },
        )
        queue = instance
        run.value = readRun()
        observeEvents(instance)
        instance.start()
        if (run.value?.paused == true) instance.pause()

        scope.launch {
            combine(run, instance.state, current, monitor.observeConnectivity) { r, qs, title, _ ->
                SnapshotBackfillState(r, phaseOf(r, qs), title)
            }.collect { _state.value = it }
        }
        scope.launch {
            instance.state.collect { if (it is QueueState.Idle) checkFinished() }
        }
        // 网络回来立刻再看一眼队列，不等兜底轮询（同 PixivActionQueue.observeGateSignals）。
        scope.launch {
            monitor.observeConnectivity.collect { online -> if (online) instance.kick() }
        }
        // 换账号：记录按账号分开存，页面要看到的是当前账号这一轮。
        scope.launch {
            withContext(Dispatchers.Main) {
                SessionManager.loggedInAccount.observeForever { reloadForAccount() }
            }
        }
    }

    // ── 页面调用的控制 ───────────────────────────────────────────────────────

    fun lastConfig(): SnapshotBackfillConfig = SnapshotBackfillConfig(
        scope = SnapshotBackfillScope.entries.firstOrNull { it.name == prefs.decodeString(KEY_SCOPE) }
            ?: SnapshotBackfillScope.ALL,
        includeComments = prefs.decodeBool(KEY_COMMENTS, false),
        // 与详情页「离线化」同一个默认：跟随全局的「展示原图」。
        includeOriginal = prefs.decodeBool(KEY_ORIGINAL, Shaft.sSettings.isShowOriginalPreviewImage()),
        wifiOnly = prefs.decodeBool(KEY_WIFI_ONLY, true),
    )

    fun begin(config: SnapshotBackfillConfig) {
        prefs.encode(KEY_SCOPE, config.scope.name)
        prefs.encode(KEY_COMMENTS, config.includeComments)
        prefs.encode(KEY_ORIGINAL, config.includeOriginal)
        prefs.encode(KEY_WIFI_ONLY, config.wifiOnly)
        val q = queue ?: return
        val owner = SessionManager.loggedInUid
        if (owner <= 0L) return
        scope.launch {
            control.withLock {
                q.clearPending()
                q.clearFailed()
                synchronized(formalLock) { formalIds = null }
                val fresh = SnapshotBackfillRun(
                    runId = UUID.randomUUID().toString(),
                    owner = owner,
                    startedAt = System.currentTimeMillis(),
                    scopeName = config.scope.name,
                    includeComments = config.includeComments,
                    includeOriginal = config.includeOriginal,
                    wifiOnly = config.wifiOnly,
                    scanChains = config.scope.restricts.size,
                )
                write(fresh)
                q.resume()
                config.scope.restricts.forEach { q.enqueueAndWait(backfillScanRequest(fresh.runId, owner, it)) }
            }
        }
    }

    fun pause() {
        val r = run.value ?: return
        update(r.runId) { it.copy(paused = true) }
        queue?.pause()
    }

    fun resume() {
        val r = run.value ?: return
        update(r.runId) { it.copy(paused = false, pausedForStorage = false) }
        queue?.resume()
    }

    /** 撤掉还没开始的作品并结束这一轮；已经生成的快照保留。 */
    fun stop() {
        val q = queue ?: return
        scope.launch {
            control.withLock {
                q.clearPending()
                q.clearFailed()
                write(null)
                q.resume()
            }
        }
    }

    /** 把重试耗尽的作品（含没读完的收藏列表）放回队列。 */
    fun retryFailed() {
        val q = queue ?: return
        val r = run.value ?: return
        scope.launch {
            control.withLock {
                update(r.runId) {
                    it.copy(
                        failed = 0,
                        scanChains = it.scanChains + it.failedScans,
                        failedScans = 0,
                        finishedAt = 0L,
                    )
                }
                q.retryAllFailed()
            }
        }
    }

    /** 看完结果，收起这一轮，回到可以重新开始的状态。 */
    fun dismiss() = stop()

    // ── handler 调用 ─────────────────────────────────────────────────────────

    internal fun isActive(runId: String): Boolean = run.value?.runId == runId

    internal val includeComments: Boolean get() = run.value?.includeComments == true
    internal val includeOriginal: Boolean get() = run.value?.includeOriginal == true

    internal suspend fun enqueue(request: ActionRequest) {
        checkNotNull(queue).enqueueAndWait(request)
    }

    /**
     * 是否已有正式快照。正式库每读一次就要把每个快照的 manifest 解析一遍，几千件收藏逐件去读
     * 是几千次全库扫描；所以每轮只读一次，生成 / 转正后由 [markFormal] 补进来。
     * 代价：补齐期间用户在详情页手动生成的那件，轮到它时可能再生成一份。
     */
    internal fun hasFormal(illustId: Long): Boolean = synchronized(formalLock) {
        val ids = formalIds ?: SnapshotRepository.list(app).mapTo(HashSet()) { it.manifest.illustId }
            .also { formalIds = it }
        illustId in ids
    }

    internal fun markFormal(illustId: Long) {
        synchronized(formalLock) { formalIds?.add(illustId) }
    }

    internal fun setCurrent(title: String?) {
        current.value = title
    }

    internal fun pauseForLowStorage() {
        val r = run.value ?: return
        update(r.runId) { it.copy(paused = true, pausedForStorage = true) }
        queue?.pause()
    }

    /** 只改属于 [runId] 的那一轮：停止或重开之后，旧行晚到的计数直接丢掉。 */
    @Synchronized
    internal fun update(runId: String, transform: (SnapshotBackfillRun) -> SnapshotBackfillRun) {
        val r = run.value ?: return
        if (r.runId != runId) return
        write(transform(r))
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 按这一轮自己的账号存：换账号的瞬间晚到的计数不能写进新账号名下。 */
    @Synchronized
    private fun write(value: SnapshotBackfillRun?) {
        if (value == null) {
            prefs.removeValueForKey(runKey(run.value?.owner ?: SessionManager.loggedInUid))
        } else {
            prefs.encode(runKey(value.owner), Shaft.sGson.toJson(value))
        }
        run.value = value
    }

    private fun readRun(): SnapshotBackfillRun? =
        prefs.decodeString(runKey(SessionManager.loggedInUid))
            ?.let { runCatching { Shaft.sGson.fromJson(it, SnapshotBackfillRun::class.java) }.getOrNull() }

    private fun reloadForAccount() {
        val fresh = readRun()
        run.value = fresh
        val q = queue ?: return
        // resume 自带唤醒：owner 变了，队列要按新账号重新取行。
        if (fresh?.paused == true) q.pause() else q.resume()
    }

    /**
     * 失败事件里只关心计数：失败行留在库里，「重试失败」靠 [ActionQueue.retryAllFailed] 原样放回。
     * 订阅时机与逐条兜底同 PixivActionQueue.observeFailures。
     */
    private fun observeEvents(instance: ActionQueue) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            instance.events.collect { event ->
                try {
                    if (event is ActionEvent.Failed) onFailed(event)
                    if (event !is ActionEvent.Retrying) checkFinished()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "handling backfill event failed")
                }
            }
        }
    }

    private fun onFailed(event: ActionEvent.Failed) {
        val action = event.action
        Timber.tag(TAG).w("backfill failed: type=%s reason=%s", action.type, event.reason)
        when (action.type) {
            SnapshotBackfillTypes.ILLUST -> {
                val payload = action.parsePayload<BackfillIllustPayload>() ?: return
                update(payload.runId) { it.copy(failed = it.failed + 1) }
            }
            SnapshotBackfillTypes.SCAN -> {
                val payload = action.parsePayload<BackfillScanPayload>() ?: return
                update(payload.runId) {
                    it.copy(scanChains = (it.scanChains - 1).coerceAtLeast(0), failedScans = it.failedScans + 1)
                }
            }
        }
    }

    private suspend fun checkFinished() {
        val q = queue ?: return
        control.withLock {
            val r = run.value ?: return
            if (r.finishedAt > 0L || r.paused) return
            if (q.pendingCount() > 0) return
            update(r.runId) { it.copy(finishedAt = System.currentTimeMillis(), scanChains = 0) }
        }
    }

    private fun phaseOf(r: SnapshotBackfillRun?, qs: QueueState): SnapshotBackfillPhase = when {
        r == null -> SnapshotBackfillPhase.IDLE
        r.finishedAt > 0L -> SnapshotBackfillPhase.FINISHED
        r.pausedForStorage -> SnapshotBackfillPhase.PAUSED_LOW_STORAGE
        r.paused -> SnapshotBackfillPhase.PAUSED
        qs is QueueState.CoolingDown -> SnapshotBackfillPhase.COOLING_DOWN
        qs is QueueState.Suspended -> when {
            !SessionManager.isLoggedIn -> SnapshotBackfillPhase.WAITING_LOGIN
            r.wifiOnly && isMetered() -> SnapshotBackfillPhase.WAITING_WIFI
            else -> SnapshotBackfillPhase.WAITING_NETWORK
        }
        else -> SnapshotBackfillPhase.RUNNING
    }

    /**
     * 当前网络是否计费（移动数据 / 热点）。拿不到时按不计费处理：部分 ROM 查网络状态会抛
     * SecurityException（同 NetworkMonitor 的注释），按计费处理的话「仅 Wi-Fi」会永远等下去。
     */
    private fun isMetered(): Boolean = try {
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    } catch (e: SecurityException) {
        false
    }

    private fun runKey(owner: Long) = "run_$owner"

    companion object {
        private const val TAG = "SnapshotBackfill"
        private const val PREFS_ID = "snapshot-backfill"
        private const val DATABASE_NAME = "snapshot_backfill_queue.db"
        private const val KEY_SCOPE = "last_scope"
        private const val KEY_COMMENTS = "last_comments"
        private const val KEY_ORIGINAL = "last_original"
        private const val KEY_WIFI_ONLY = "last_wifi_only"

        /** 每件要拉一次详情（带评论时还有评论与回复），间隔同收藏队列的保守值。 */
        private const val MIN_GAP_MS = 2_000L

        /** 计费网络 → Wi-Fi 这类变化不会触发「恢复联网」信号，靠缩短兜底轮询来接上。 */
        private const val IDLE_POLL_MS = 15_000L

        /** 剩余空间低于它就自动暂停：快照落在 App 内部存储，写满会连带影响整个 App。 */
        const val MIN_FREE_BYTES: Long = 1L shl 30
    }
}
