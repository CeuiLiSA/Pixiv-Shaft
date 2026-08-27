package ceui.pixiv.ui.bulk

import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.http.Retro
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [QueueDownloadManager] 用到的两个无状态 helper。抽到独立文件让 manager 文件
 * 减少 60+ 行，且这两个工具天然不依赖 manager 内部任何 mutable 状态，移出去
 * 行为完全等价。
 */

/**
 * [Manager.content] 的浅拷贝快照。底层已是 CopyOnWriteArrayList + synchronized 拷贝，
 * 任何线程调用都不会 CME，无需重试。
 */
internal fun snapshotManagerContent(): List<DownloadItem> = Manager.get().contentSnapshot()

/**
 * 解析 [row] 对应的 [Illust]，优先级：
 *   1. ObjectPool 命中（用户最近浏览过 / 同一会话之前已解析过）
 *   2. 反序列化 [DownloadQueueEntity.illustGson] —— 入队时存进 DB 的 JSON，
 *      冷启动 100+ PENDING 都靠这条路，0 次网络请求
 *   3. 回退 API getIllustByID —— 只有老版本入队的行 illustGson=null 才走，
 *      不会 429（量极少）
 * 解析成功的都灌一次 ObjectPool，下一次同 id 命中第 1 步。
 */
internal suspend fun resolveIllust(row: DownloadQueueEntity): Illust {
    val illustId = row.illustId
    // 1) 内存池
    val cached = runCatching { ObjectPool.getIllust(illustId).value }.getOrNull()
    if (cached != null) return cached

    // 2) DB 里入队时存的 JSON —— 主路径
    val gson = row.illustGson
    if (!gson.isNullOrEmpty()) {
        val parsed = runCatching { Shaft.sGson.fromJson(gson, Illust::class.java) }
            .getOrNull()
        if (parsed != null) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { ObjectPool.updateIllust(parsed) }
            }
            return parsed
        }
        Timber.tag(TAG).w("[QUEUE-CONSUMER] illustGson parse failed illust=$illustId, falling back to API")
    }

    // 3) 老行 fallback：API 拉一次，这一路不应该是常态
    val resp = Retro.getAppApi().getIllustByID(illustId)
    val bean = resp.illust
        ?: throw IllegalStateException("getIllustByID returned null for $illustId")
    withContext(Dispatchers.Main.immediate) {
        runCatching { ObjectPool.updateIllust(bean) }
    }
    return bean
}

private const val TAG = "QueueConsumerHelpers"
