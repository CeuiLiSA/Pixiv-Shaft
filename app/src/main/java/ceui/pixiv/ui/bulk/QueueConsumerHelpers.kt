package ceui.pixiv.ui.bulk

import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.download.StageStore
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
    val resp = Client.appApi.getIllustByID(illustId)
    val bean = resp.illust
        ?: throw IllegalStateException("getIllustByID returned null for $illustId")
    withContext(Dispatchers.Main.immediate) {
        runCatching { ObjectPool.updateIllust(bean) }
    }
    return bean
}

/**
 * 这一页"已经落了多少字节"的可靠读数。
 *
 * 优先 stage 文件（`.part`）的实际长度：它是传输线程直接写盘的产物，跨线程、跨会话都在，
 * 冷启动后也是真实值。[ceui.lisa.core.DownloadItem.getCurrentSize] 只是主线程异步写的
 * 显示值（冷启动后必为 0、主线程一忙就滞后），拿它当"这一轮有没有往前走"的判据，会让
 * 重试圈数变成主线程负载的函数。
 *
 * 直写路径（file:// / gif zip）没有 `.part`，`url` 为空时也取不到，两种情况都退回
 * `currentSize`；`currentSize` 为负按 0 处理。
 *
 * 抽成纯函数（而不是留在 QueueDownloadManager 的私有方法里）是为了这条优先级能被单测
 * 钉住，而不是只能靠读代码。
 */
internal fun durableBytesOf(stageDir: java.io.File, url: String?, currentSize: Long): Long {
    val staged = runCatching {
        url?.let { StageStore.partFile(stageDir, StageStore.keyForUrl(it)).length() }
    }.getOrNull() ?: 0L
    return maxOf(staged, currentSize.coerceAtLeast(0))
}

/**
 * 一条 illust 留在 content 里的页是否**全部**确定失败 —— 整条判 FAILED 的唯一条件。
 *
 * [states] 取 [DownloadItem.getState]：暂停页返回 PAUSED，自然不算失败。SUCCESS 页只是
 * 还没被主线程从 content 摘掉（摘除是 postMain 的异步动作），必须继续等；把它算成
 * "已 settle"会让整条误判失败 → 回退 PENDING → 同一页被重新下载。
 */
internal fun allPagesFailed(states: List<Int>): Boolean =
    states.isNotEmpty() && states.all { it == DownloadItem.DownloadState.FAILED }

/**
 * 拉入一行时还要派发的页：不在 content 里（[present] 由残留页继续跑）、也不是本行已经
 * 落盘的（[doneByThisRow]），按页号升序。
 */
internal fun pagesToDispatch(pageCount: Int, present: Set<Int>, doneByThisRow: Set<Int>): ArrayDeque<Int> =
    ArrayDeque((0 until pageCount).filter { it !in present && it !in doneByThisRow })

private const val TAG = "QueueConsumerHelpers"
