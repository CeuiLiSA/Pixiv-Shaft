package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.services.appServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 自动快照引擎：目前只响应“收藏时生成离线快照”这一种信号。
 *
 * 静默生成：不弹窗、不 toast；失败只记日志，不重试轰炸。
 */
object AutoSnapshotEngine {

    private const val TAG = "AutoSnapshot"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "auto snapshot scope crashed") }
    )

    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** 由 PixivActionQueue 在收藏请求被服务端确认成功后调用（事件驱动）。 */
    fun onBookmarkConfirmed(illust: Illust) {
        if (!Shaft.sSettings.isAutoSnapshotOnBookmark) return
        val id = illust.id
        if (id <= 0L) return
        if (!inFlight.add(id)) return

        scope.launch {
            try {
                val appContext = Shaft.getContext()
                // 无网/弱网不硬拉，静默跳过。
                if (appContext.appServices().networkStateManager.networkState.value?.isOnline != true) return@launch
                // 已有正式快照时不生成；已有同作品自动快照时不重复生成。
                val formalExists = SnapshotRepository.list(appContext).any { it.manifest.illustId == id }
                if (formalExists) return@launch
                val autoExists = AutoSnapshotRepository.listAuto(appContext).any { it.manifest.illustId == id }
                if (autoExists) return@launch

                SnapshotGenerator.generateAuto(appContext, illust)
                AutoSnapshotRepository.enforceAutoQuota(appContext)
                Timber.tag(TAG).i("auto snapshot generated, illustId=%d", id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "auto snapshot failed, illustId=%d", id)
            } finally {
                inFlight.remove(id)
            }
        }
    }
}