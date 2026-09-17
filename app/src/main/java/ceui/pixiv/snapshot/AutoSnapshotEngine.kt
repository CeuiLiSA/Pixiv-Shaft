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
 * - 插画/漫画自动生成快照：根据“反复进入”和“长时间驻留”两个本地行为信号触发。
 *
 * 生成时机：两个信号都只在详情页**真离开**时评估一次，由 [onArtworkPageLeft] 统一启动。
 * 「真离开」= 宿主仍 RESUMED 时的 onPause（横滑到相邻作品 / 进程内导航离开）、宿主停止
 * （切后台 / 页面结束）、或页面销毁。被自家半透明层（二级大图页及更上层）盖住时宿主自己
 * 先 paused —— 视觉没离开，计时继续走，不结算也不评估，回到详情页时接着算同一段停留。
 *
 * 静默生成：不弹窗、不 toast；失败只记日志，不重试轰炸。
 * 选择性启用：收藏走 [Shaft.sSettings.isAutoSnapshotOnBookmark]，行为走
 * [Shaft.sSettings.isAutoSnapshotOnIllustManga]，开关关闭时不采集、不生成。
 */
object AutoSnapshotEngine {

    private const val TAG = "AutoSnapshot"

    /** 单次停留超过该时长视为“长时间驻留”；被自家覆盖层盖住的时间也算在同一段里。 */
    private const val DWELL_THRESHOLD_MS = 60_000L

    /** 7 天窗口内进入次数达到该值视为“反复进入”。 */
    private const val REVISIT_THRESHOLD = 3

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "auto snapshot scope crashed") }
    )

    private val pending = AutoSnapshotPendingRequests(capacity = 32)

    // 记录按页面回调顺序落盘；不能让主线程等待 MMKV 初始化、JSON 解析或 prune 的锁。
    private val behaviorDispatcher = Dispatchers.IO.limitedParallelism(1)
    // 翻页可以不断触发新作品，IO dispatcher 本身不会限制挂起中的下载数。
    private val generationPermit = Semaphore(1)

    /** 由各页面持有，避免多窗口打开同一作品时互相覆盖计时。只消费一次，不持有 View。 */
    class ArtworkVisit internal constructor(val illustId: Long, private val startedAt: Long) {
        private var finished = false

        @Synchronized
        internal fun finish(now: Long): Long? {
            if (finished) return null
            finished = true
            return (now - startedAt).coerceAtLeast(0L)
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
     */
    fun onArtworkPageVisible(illustId: Long, type: String?, countAsEntry: Boolean = true): ArtworkVisit? {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga || illustId <= 0L || type == "ugoira") return null
        val visit = ArtworkVisit(illustId, SystemClock.elapsedRealtime())
        if (!countAsEntry) return visit
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordVisit(illustId, type, now)
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
     * 详情页**真离开**时调用：插画/漫画自动生成的唯一启动点。
     *
     * 停表 → 记一段停留 → 评估两个信号 → 静默生成。[evaluate] 为假时只结算停留：
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
            val signal = exitTriggerSignal(dwellMs, AutoSnapshotBehaviorStore.read(visit.illustId))
                ?: return@launch
            Timber.tag(TAG).i(
                "auto snapshot trigger, illustId=%d, signal=%s, dwellMs=%d",
                visit.illustId,
                signal,
                dwellMs,
            )
            maybeTriggerBehaviorAuto(visit.illustId, signal)
        }
    }

    /**
     * 真离开时的触发判定（纯函数，便于单测）：长时间驻留优先，其次反复进入。
     *
     * 停留时长用本次结算值而不是记录里的 lastDwellMs —— 行为库写失败时不该顺带把触发也判丢。
     */
    internal fun exitTriggerSignal(dwellMs: Long, record: AutoSnapshotBehaviorRecord?): String? = when {
        dwellMs >= DWELL_THRESHOLD_MS -> AutoSnapshotBehaviorStore.SIGNAL_DWELL
        (record?.recentVisits?.size ?: 0) >= REVISIT_THRESHOLD -> AutoSnapshotBehaviorStore.SIGNAL_REVISIT
        else -> null
    }

    private fun maybeTriggerBehaviorAuto(illustId: Long, signal: String) {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        val illust = ObjectPool.get<Illust>(illustId).value ?: return
        if (illust.isGif()) return
        launchAutoSnapshot(illust, signal)
    }

    /** 统一异步生成入口：去重、网络检查、已有快照检查、静默生成、配额与记录。 */
    private fun launchAutoSnapshot(illust: Illust, signal: String?) {
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
                        AutoSnapshotBehaviorStore.markAutoSnapshotGenerated(id, ready.behaviorSignal)
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
