package ceui.pixiv.snapshot

import android.os.SystemClock
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.services.appServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * 自动快照引擎：
 * - 收藏时生成离线快照（已有）；
 * - 插画/漫画自动生成快照：根据本地行为**评分**触发，见 [AutoSnapshotScoring]。
 *
 * 生成时机：评分只在详情页**真离开**时算一次，由 [onArtworkPageLeft] 统一启动。
 * 「真离开」只剩两个落点：宿主停止（切后台 / 上层页压上来 / 页面结束）、页面销毁。
 *
 * 与之相对的是**挂起**：宿主还在、只是本页不再被看（横滑到相邻作品、V3 视口滚出作品范围）时
 * 走 [onArtworkPageSuspended]，停表但保留凭证；重新被看时 [onArtworkPageResumed] 接着算同一段
 * 停留，不结算也不评估。被自家半透明层（二级大图页及更上层）盖住时宿主自己先 paused —— 视觉
 * 没离开，表从没停过，回来是空操作。
 *
 * 静默生成：不弹窗、不 toast；失败只记日志，不重试轰炸。
 * 选择性启用：收藏走 [Shaft.sSettings.isAutoSnapshotOnBookmark]，行为走
 * [Shaft.sSettings.isAutoSnapshotOnIllustManga]，开关关闭时不采集、不生成。
 */
object AutoSnapshotEngine {

    private const val TAG = "AutoSnapshot"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "auto snapshot scope crashed") }
    )

    private val pending = AutoSnapshotPendingRequests(capacity = 32)

    // 记录按页面回调顺序落盘；不能让主线程等待 MMKV 初始化、JSON 解析或 prune 的锁。
    private val behaviorDispatcher = Dispatchers.IO.limitedParallelism(1)
    // 翻页可以不断触发新作品，IO dispatcher 本身不会限制挂起中的下载数。
    private val generationPermit = Semaphore(1)

    /**
     * 由各页面持有，避免多窗口打开同一作品时互相覆盖计时。只消费一次，不持有 View。
     *
     * 可挂起：本页被降级（横滑到相邻作品）或滚出作品范围时 [suspend]，重新被看时 [resume]，
     * 累计停留跨挂起延续。挂起期间的时间不计入，只有 [finish] 才结算。
     */
    class ArtworkVisit internal constructor(val illustId: Long, startedAt: Long) {
        private var accumulatedMs = 0L
        private var runningSince = startedAt
        private var running = true
        private var finished = false

        /** 停表但保留凭证。已结束 / 已挂起时是空操作 —— 滚动回调每帧都可能调它。 */
        @Synchronized
        internal fun suspend(now: Long) {
            if (finished || !running) return
            accumulatedMs += (now - runningSince).coerceAtLeast(0L)
            running = false
        }

        /** 从此刻接着累加。从没挂起过（表一直在走）时是空操作。 */
        @Synchronized
        internal fun resume(now: Long) {
            if (finished || running) return
            runningSince = now
            running = true
        }

        @Synchronized
        internal fun finish(now: Long): Long? {
            if (finished) return null
            finished = true
            return accumulatedMs + if (running) (now - runningSince).coerceAtLeast(0L) else 0L
        }
    }

    /** 由 PixivActionQueue 在收藏请求被服务端确认成功后调用（事件驱动）。 */
    fun onBookmarkConfirmed(illust: Illust) {
        if (!Shaft.sSettings.isAutoSnapshotOnBookmark) return
        launchAutoSnapshot(illust, signal = null)
    }

    /**
     * 详情页可见（onResume）时调用：返回本次可见的计时凭证。
     *
     * 只在 [countAsEntry] 为真时记一次「进入」。凭证还没结算时调用方不会重复调本方法
     * （进二级大图页再回来就是这种），所以这里要区分的只有「同一个页面实例的再次可见」：
     * 切后台回来、横滑滑回都属于这一类，不该算新的一次进入。
     *
     * [pageCount] 一并落盘，供评分按作品体量归一化驻留分；未知时传 0。
     */
    fun onArtworkPageVisible(
        illustId: Long,
        type: String?,
        pageCount: Int = 0,
        countAsEntry: Boolean = true,
    ): ArtworkVisit? {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga || illustId <= 0L || type == "ugoira") return null
        val visit = ArtworkVisit(illustId, SystemClock.elapsedRealtime())
        if (!countAsEntry) return visit
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordVisit(illustId, type, pageCount, now)
        }
        return visit
    }

    /**
     * 用户在设置里改了自动快照总大小上限：按新值立刻淘汰一次。
     *
     * 淘汰本来只挂在「又生成了一份」之后，于是把上限从 200 MB 拖到 10 MB 之后什么都不会
     * 发生，在用户看来就是这个设置没生效。
     *
     * 走引擎自己的 scope 和 [generationPermit]：快照目录的增删只由这一个许可串行化，
     * 另起一条线程去 enforce 就会和正在生成的那一轮抢同一批目录。scope 带
     * CoroutineExceptionHandler，这里不需要再兜一层 try。
     */
    fun onAutoQuotaLimitChanged() {
        scope.launch {
            generationPermit.withPermit {
                AutoSnapshotRepository.enforceAutoQuota(Shaft.getContext())
            }
        }
    }

    /**
     * 详情页**真离开**时调用（宿主停止 / 页面销毁）：插画/漫画自动生成的唯一启动点。
     *
     * 停表 → 记一段停留 → 算一次行为评分（[AutoSnapshotScoring]）→ 静默生成。[evaluate] 为假时只结算停留：
     * 旋屏重建属于这一类，视觉没离开不该触发，但停留确实发生了，记下来不丢。
     *
     * 凭证只消费一次，所以 onStop 之后接着走 onDestroyView 是空操作。
     */
    fun onArtworkPageLeft(visit: ArtworkVisit?, evaluate: Boolean) {
        val dwellMs = visit?.finish(SystemClock.elapsedRealtime()) ?: return
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        if (dwellMs <= 0L) return
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordDwell(visit.illustId, dwellMs, now)
            // 每次真离开都留一条：验收时能直接看出「结算了多少秒」和「是不是真离开」，
            // 不必靠最终那条 generated 反推。
            Timber.tag(TAG).i(
                "auto snapshot settle, illustId=%d, dwellMs=%d, evaluate=%s",
                visit.illustId,
                dwellMs,
                evaluate,
            )
            if (!evaluate) return@launch
            val breakdown = AutoSnapshotScoring.score(
                record = AutoSnapshotBehaviorStore.read(visit.illustId),
                dwellMs = dwellMs,
                now = now,
            )
            // 分项日志是这套评分唯一的验收手段：能直接看出「为什么触发 / 为什么没触发」。
            Timber.tag(TAG).i(
                "auto snapshot score, illustId=%d, total=%d, core=%d, strongest=%s, dwell=%d, revisit=%d, coverage=%d, attention=%d, zoom=%d, dwellMs=%d",
                visit.illustId,
                breakdown.total,
                breakdown.core,
                breakdown.strongest,
                breakdown.dwell,
                breakdown.revisit,
                breakdown.coverage,
                breakdown.attention,
                breakdown.zoom,
                dwellMs,
            )
            if (breakdown.total < AutoSnapshotScoring.SCORE_THRESHOLD) return@launch
            Timber.tag(TAG).i(
                "auto snapshot trigger, illustId=%d, score=%d, strongest=%s",
                visit.illustId,
                breakdown.total,
                breakdown.strongest,
            )
            maybeTriggerBehaviorAuto(visit.illustId, AutoSnapshotBehaviorStore.SIGNAL_SCORE, breakdown.total)
        }
    }

    /**
     * 本页还在宿主里、只是不再被看：横滑到相邻作品（本页被 setMaxLifecycle 降到 STARTED），
     * 或 V3 视口滚出了作品范围。停表但**保留凭证**，不结算也不评估 —— 用户随时会滑回来 / 滚回去。
     *
     * 幂等：滚动回调每帧都可能调它。
     */
    fun onArtworkPageSuspended(visit: ArtworkVisit?) {
        visit?.suspend(SystemClock.elapsedRealtime())
    }

    /**
     * 挂起过的凭证重新被看：从此刻接着算同一段停留。被半透明层盖住期间表从没停过，那时这里是空操作。
     */
    fun onArtworkPageResumed(visit: ArtworkVisit?) {
        visit?.resume(SystemClock.elapsedRealtime())
    }

    /**
     * 二级大图会话结束时调用：把逐页浏览观测（每页驻留 + 是否缩放过）与本次覆盖率写进行为库。
     *
     * **只观测，不评估、不触发** —— 与 [onArtworkPageLeft] 的启动点无关，不参与生成判定。
     * 与其它入口一样，只在「插画/漫画自动生成快照」开启时采集。
     */
    fun onViewerSessionEnd(
        illustId: Long,
        pages: List<AutoSnapshotViewerPageSample>,
        pageCount: Int,
    ) {
        if (illustId <= 0L || pageCount <= 0 || pages.isEmpty()) return
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        val viewedPages = pages.count { it.ms > 0L }
        if (viewedPages <= 0) return
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordViewerSession(illustId, pages, pageCount, viewedPages, now)
            Timber.tag(TAG).i(
                "auto snapshot viewer session, illustId=%d, pageCount=%d, viewedPages=%d, zoomedPages=%d",
                illustId,
                pageCount,
                viewedPages,
                pages.count { it.zoomed },
            )
        }
    }

    private fun maybeTriggerBehaviorAuto(illustId: Long, signal: String, score: Int) {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        val illust = ObjectPool.get<Illust>(illustId).value ?: return
        if (illust.isGif()) return
        launchAutoSnapshot(illust, signal, score)
    }

    /** 统一异步生成入口：去重、网络检查、已有快照检查、静默生成、配额与记录。 */
    private fun launchAutoSnapshot(illust: Illust, signal: String?, score: Int? = null) {
        val id = illust.id
        val request = pending.add(illust, signal) ?: return

        scope.launch {
            try {
                generationPermit.withPermit {
                    // 等待期间可能关闭了开关；排队中的任务必须重新确认。
                    val ready = pending.ready(
                        request,
                        bookmarkEnabled = Shaft.sSettings.isAutoSnapshotOnBookmark,
                        behaviorEnabled = Shaft.sSettings.isAutoSnapshotOnIllustManga,
                    ) ?: return@withPermit
                    val appContext = Shaft.getContext()
                    // 无网/弱网不硬拉，静默跳过。
                    if (appContext.appServices().networkStateManager.networkState.value?.isOnline != true) return@withPermit
                    // 已有正式快照时不生成；已有同作品自动快照时不重复生成。
                    val formalExists = SnapshotRepository.list(appContext).any { it.manifest.illustId == id }
                    if (formalExists) return@withPermit
                    val autoExists = AutoSnapshotRepository.listAuto(appContext).any { it.manifest.illustId == id }
                    if (autoExists) return@withPermit

                    SnapshotGenerator.generateAuto(appContext, ready.illust)
                    AutoSnapshotRepository.enforceAutoQuota(appContext)
                    if (ready.behaviorSignal != null && Shaft.sSettings.isAutoSnapshotOnIllustManga) {
                        AutoSnapshotBehaviorStore.markAutoSnapshotGenerated(id, ready.behaviorSignal, score)
                    }
                    Timber.tag(TAG).i("auto snapshot generated, illustId=%d, signal=%s", id, ready.behaviorSignal ?: "bookmark")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "auto snapshot failed, illustId=%d", id)
            } finally {
                pending.finish(request)
            }
        }
    }
}
