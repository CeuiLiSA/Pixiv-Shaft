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
 * - 插画/漫画自动生成快照：根据“反复浏览”和“长时间驻留”两个本地行为信号触发。
 *
 * 静默生成：不弹窗、不 toast；失败只记日志，不重试轰炸。
 * 选择性启用：收藏走 [Shaft.sSettings.isAutoSnapshotOnBookmark]，行为走
 * [Shaft.sSettings.isAutoSnapshotOnIllustManga]，开关关闭时不采集、不生成。
 */
object AutoSnapshotEngine {

    private const val TAG = "AutoSnapshot"

    /** 单次停留超过该时长视为“长时间驻留”。 */
    private const val DWELL_THRESHOLD_MS = 60_000L

    /** 7 天窗口内打开次数达到该值视为“反复浏览”。 */
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

    /** 详情页真正可见（onResume）时调用：记录一次打开，并检查是否达到反复浏览阈值。 */
    fun onArtworkPageVisible(illustId: Long, type: String?): ArtworkVisit? {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga || illustId <= 0L || type == "ugoira") return null
        val visit = ArtworkVisit(illustId, SystemClock.elapsedRealtime())
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordVisit(illustId, type, now)
            maybeTriggerRevisit(illustId)
        }
        return visit
    }

    /** 详情页离开（onPause）时调用：记录停留时长，并检查是否达到长时间驻留阈值。 */
    fun onArtworkPageHidden(visit: ArtworkVisit?) {
        val dwellMs = visit?.finish(SystemClock.elapsedRealtime()) ?: return
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        if (dwellMs <= 0L) return
        val now = System.currentTimeMillis()
        scope.launch(behaviorDispatcher) {
            if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return@launch
            AutoSnapshotBehaviorStore.recordDwell(visit.illustId, dwellMs, now)
            if (dwellMs >= DWELL_THRESHOLD_MS) {
                maybeTriggerBehaviorAuto(visit.illustId, AutoSnapshotBehaviorStore.SIGNAL_DWELL)
            }
        }
    }

    private fun maybeTriggerRevisit(illustId: Long) {
        val record = AutoSnapshotBehaviorStore.read(illustId) ?: return
        if (record.recentVisits.size >= REVISIT_THRESHOLD) {
            maybeTriggerBehaviorAuto(illustId, AutoSnapshotBehaviorStore.SIGNAL_REVISIT)
        }
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
