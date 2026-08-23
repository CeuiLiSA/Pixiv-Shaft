package ceui.pixiv.ui.bulk

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.model.ListIllust
import ceui.loxia.Illust
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.db.queue.WorkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * 细粒度 fetcher 事件：dialog 用它驱动 CLI 风格的实时 verbose 显示。
 *
 * 事件流（典型一页）：
 *   Started
 *   ── 第 1 页 ──
 *   Networking(1, "/v1/user/illusts")    ↩ 5xx?
 *   PageReceived(1, 30, latency=312ms)
 *   DbBatchStart(30)
 *   DbBatchDone(30, latency=1.2ms)
 *   Enqueued(total=30)
 *   ── 第 2 页 ──
 *   RateLimit(waitMs=1500)               ↩ 倒计时
 *   Networking(2, "<next_url 截断>")
 *   ...
 *   Done(total, elapsedMs)
 */
sealed class FetchEvent {
    /** subtitle 由 source 提供，dialog 用它拼标题（旧字段 userId 已废弃，避免 dialog 写死 user 概念）。 */
    data class Started(val taskName: String, val subtitle: String) : FetchEvent()
    data class Networking(val pageIndex: Int, val endpoint: String) : FetchEvent()
    data class PageReceived(val pageIndex: Int, val pageSize: Int, val latencyMs: Long, val totalSoFar: Int) : FetchEvent()
    data class DbBatchStart(val size: Int) : FetchEvent()
    data class DbBatchDone(val size: Int, val latencyMs: Long) : FetchEvent()
    data class Enqueued(val totalSoFar: Int) : FetchEvent()
    data class RateLimit(val waitMs: Long) : FetchEvent()
    data class Done(val total: Int, val elapsedMs: Long, val pageCount: Int) : FetchEvent()
    data class Errored(val message: String, val pageIndex: Int) : FetchEvent()

    // ── 合并下载 / 通用 CLI 扩展 ──
    // batch illust 的 page→db→enqueue 三件套对合并下载小说语义不对,加几个更准确的事件:
    // 章节合并 / 插画抓取 / 文件写入。dialog 把它们渲染成对应状态行 + 日志行;batch
    // illust 流不 emit 这些,旧行为完全不动。

    /** 一章解析完成并合到 buffer 里。[chapterIndex] 1-based,[totalChapters] 预估总数。 */
    data class ChapterMerged(
        val chapterIndex: Int,
        val totalChapters: Int,
        val title: String,
        val latencyMs: Long,
    ) : FetchEvent()

    /** 一张插画从网络抓回并编入 EPUB images/。[bytes] 编码后 JPEG 长度。 */
    data class ImageFetched(val key: String, val bytes: Int) : FetchEvent()

    /** 最终输出文件准备开始写盘(zip/pdf/txt 等)。 */
    data class WritingFile(val filename: String) : FetchEvent()

    /** 任意自定义日志行,producer 想追加什么就 emit 什么;不影响 phase/status。 */
    data class Log(val line: String) : FetchEvent()

    /** 非致命警告(如某章抓取失败被跳过)。日志里加显眼标记,但不进 FAILED 终态。 */
    data class Warning(val message: String) : FetchEvent()
}

/**
 * 通用批量翻页 fetcher：只负责
 *   1. 调 source.firstPage / source.nextPage 直到 nextUrl 空
 *   2. 每页之间等 [RATE_LIMIT_MS]
 *   3. emit 进度事件给 [FetchProgressDialog]
 *   4. 把每页交给 [onPage] 处理（写库、入队等业务），它返回这一页"成功处理"了多少条
 *   5. 终态时调 [onTerminal]，让业务层做收尾（例：唤醒下载消费者）
 *
 * 注意：
 *   - 不写死任何 illust/manga 概念。novels / users 也能复用这套循环。
 *   - "走到末尾"由 source 决定（nextUrl 空），所以推荐流这种无结尾列表绝对不能塞进来。
 */
class BulkObjectFetcher<T>(
    private val source: PaginatedObjectSource<T>,
    private val taskName: String,
    private val onPage: suspend (collector: FlowCollector<FetchEvent>, items: List<T>) -> Int,
    private val onTerminal: suspend (success: Boolean, total: Int) -> Unit = { _, _ -> },
) {

    fun fetch(): Flow<FetchEvent> = flow {
        emit(FetchEvent.Started(taskName, source.subtitle))
        val startedAt = System.currentTimeMillis()
        var pageIndex = 0
        var totalSoFar = 0

        try {
            // —— 第 1 页 ——
            pageIndex = 1
            emit(FetchEvent.Networking(pageIndex, source.endpointHint))
            val t0 = System.currentTimeMillis()
            var page = source.firstPage()
            val firstLatency = System.currentTimeMillis() - t0
            val firstList = page?.items ?: emptyList()
            emit(FetchEvent.PageReceived(pageIndex, firstList.size, firstLatency, totalSoFar + firstList.size))

            if (firstList.isNotEmpty()) {
                totalSoFar += onPage(this, firstList)
                emit(FetchEvent.Enqueued(totalSoFar))
            }

            // —— 后续页 ——
            var nextUrl = page?.nextUrl
            while (!nextUrl.isNullOrEmpty()) {
                emit(FetchEvent.RateLimit(RATE_LIMIT_MS))
                delay(RATE_LIMIT_MS)

                pageIndex++
                emit(FetchEvent.Networking(pageIndex, abbrevUrl(nextUrl)))
                val tn = System.currentTimeMillis()
                page = source.nextPage(nextUrl)
                val latency = System.currentTimeMillis() - tn
                val list = page?.items ?: emptyList()
                emit(FetchEvent.PageReceived(pageIndex, list.size, latency, totalSoFar + list.size))

                if (list.isNotEmpty()) {
                    totalSoFar += onPage(this, list)
                    emit(FetchEvent.Enqueued(totalSoFar))
                }

                nextUrl = page?.nextUrl
            }

            onTerminal(true, totalSoFar)
            emit(FetchEvent.Done(totalSoFar, System.currentTimeMillis() - startedAt, pageIndex))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户主动取消：让业务层决定要不要后处理（默认不唤醒消费者）
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "bulk fetch failed source=${source.sourceTag} page=$pageIndex")
            // 失败：把已成功处理的部分交给业务层（例：启动下载，不浪费已经做的工作）
            onTerminal(false, totalSoFar)
            emit(FetchEvent.Errored(e.message ?: e::class.java.simpleName, pageIndex))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** 翻页之间的 API 限速 —— 防 pixiv 429，debug / release 都得守。 */
        private const val RATE_LIMIT_MS = 2000L
        private const val TAG = "BulkObjectFetcher"
    }
}

// ───────────────────────── illust 专用：入队下载 ─────────────────────────

/**
 * 把 `PaginatedObjectSource<Illust>` 流式入队到 download_queue，并在终态唤醒消费者。
 *
 * 这是当前唯一的"动作"——以后如果要做 list-user 关注/list-novel 收藏等其它批量动作，
 * 再加同级的 `bulkFollowUsersFromSource(...)` 等顶层函数即可，不要复用本函数。
 */
fun bulkEnqueueIllusts(
    source: PaginatedObjectSource<Illust>,
    taskName: String,
): Flow<FetchEvent> {
    val dao: DownloadQueueDao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    return BulkObjectFetcher(
        source = source,
        taskName = taskName,
        onPage = { collector, items -> enqueueIllustPage(collector, items, source.sourceTag, dao) },
        // 任何"已入队过东西"的终态（成功 / 部分失败）都唤醒消费者，避免活儿白干
        onTerminal = { _, total -> if (total > 0) QueueDownloadManager.resume() },
    ).fetch()
}

/**
 * 处理一页 illust：序列化进 illustGson → 批量写库 → emit 进度。
 *
 * **不灌 ObjectPool**（20000 illusts × ~15KB 直接撑满内存 → OOM；setValue 还会
 *  触发可见 RecyclerView cell 刷新一轮，UI 抖动）。
 *
 * **改走 illustGson**：把 Illust 当 JSON 字符串塞进 [DownloadQueueEntity.illustGson]
 *  列。冷启动 / 队列 tab / consumer 都按需 Gson.fromJson，只有 *可见* 的 ~10 个 row
 *  会进 ObjectPool（[ceui.pixiv.ui.download.QueueListV3Fragment] adapter 里）。
 *  这是 commit a01f5831 在 LegacyBatchEnqueue 上选的同一条路；当时漏了 streaming
 *  fetcher 这条路径，导致从作者作品 / 收藏批量入队的第 2 页之后标题/缩略图丢失。
 *
 * type 取自每条 illust 自身：isGif → UGOIRA（走 [downloadUgoira] 单独管线），
 * 否则按 [Illust.getType] 落到 ILLUST/MANGA。bookmarks 这种 illust/manga/ugoira
 * 混合的场景才能正确分流。
 *
 * **不再过滤 isGif** —— 之前一刀切丢弃，现在 ugoira 走 consumer 里的独立管线
 * （getGifPackage → zip → 解压 → encodeGif → 写用户目录），跟 illust 同入一张
 * download_queue 表，状态机通用。
 */
private suspend fun enqueueIllustPage(
    collector: FlowCollector<FetchEvent>,
    list: List<Illust>,
    sourceTag: String,
    dao: DownloadQueueDao,
): Int {
    if (list.isEmpty()) return 0

    collector.emit(FetchEvent.DbBatchStart(list.size))
    val tDb = System.currentTimeMillis()
    val batchBase = System.nanoTime()
    val rows = list.mapIndexed { i, illust ->
        // toJson 在 IO 线程做，单条 ~15KB 串生成完即写库，不会驻留
        val gson = runCatching { Shaft.sGson.toJson(illust) }.getOrNull()
        val rowType = when {
            illust.isGif() -> WorkType.UGOIRA
            illust.type == WorkType.MANGA -> WorkType.MANGA
            else -> WorkType.ILLUST
        }
        DownloadQueueEntity(
            illustId = illust.id,
            type = rowType,
            seq = batchBase + i,
            sourceTag = sourceTag,
            status = QueueStatus.PENDING,
            illustGson = gson,
        )
    }
    dao.appendBatch(rows)
    val dbLatency = System.currentTimeMillis() - tDb
    collector.emit(FetchEvent.DbBatchDone(list.size, dbLatency))
    return list.size
}

private fun abbrevUrl(url: String): String {
    if (url.length <= 64) return url
    return url.take(40) + "…" + url.takeLast(20)
}

internal fun ListIllust.toPageResult(): PageResult<Illust> =
    PageResult(items = list ?: emptyList(), nextUrl = next_url)
