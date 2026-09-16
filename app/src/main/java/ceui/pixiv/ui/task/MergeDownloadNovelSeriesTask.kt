package ceui.pixiv.ui.task

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Novel
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.NovelSeriesDetail
import ceui.pixiv.api.model.WebNovel
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.ui.bulk.FetchEvent
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.MergedChapter
import ceui.pixiv.ui.novel.reader.export.MergedNovelContent
import ceui.pixiv.ui.novel.reader.export.MergedNovelWriters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 合并下载小说系列的 Flow 版 fetcher —— 旧 [MergeDownloadNovelSeriesTask] 类(自己
 * 起协程 + 一连串 Toast)已下线;现在跟 batch illust 走同一套 [FetchProgressDialog]
 * CLI 风格 dialog。caller 拿到 Flow 后 dialog.show(fm, flow, config) 即可。
 *
 * UI 入口:NovelSeriesFragment 右下「下载」→ SeriesDownloadOptionsSheet「合并下载」
 * → ExportSheet 选格式 → [bulkMergeNovelSeries]。
 *
 * 合作式取消:caller 传入 [AtomicBoolean] stopSignal,dialog 的 cancel 按钮把它置
 * true。producer 在章节循环之间检查 —— 已抓到的章节会继续走完写盘流程,产物是
 * 「截短版」合集。直接 Job.cancel 会让 emit 在 collector 端立即断,无法报 Done。
 */
class MergeDownloadNovelSeriesTask(context: Context) {

    private val ctx: Context = context.applicationContext

    fun bulkMergeNovelSeries(
        seriesDetail: NovelSeriesDetail,
        knownNovels: List<Novel>,
        format: ExportFormat,
        stopSignal: AtomicBoolean,
        allowAutoEpub: Boolean = false,
        confirmFormat: (suspend (ExportFormat) -> ExportFormat?)? = null,
    ): Flow<FetchEvent> = flow {
        val startedAt = System.currentTimeMillis()
        val seriesId = seriesDetail.id

        emit(FetchEvent.Started(
            taskName = "merge-novel-series --format=${format.extension}",
            subtitle = "/novel/series/$seriesId",
        ))

        // ── 1) 把系列所有章节列表拉完 ──
        emit(FetchEvent.Log("> resolving full chapter list…"))
        val allNovels = fetchAllNovels(seriesId, knownNovels) { event -> emit(event) }
        if (allNovels.isEmpty()) {
            emit(FetchEvent.Errored("series 没找到任何章节", 0))
            return@flow
        }
        emit(FetchEvent.Log("  ↳ ${allNovels.size} chapters resolved"))

        // ── 2) 顺序抓每一章 ──
        val chapters = mutableListOf<MergedChapter>()
        var skipped = 0
        val total = allNovels.size
        for ((idx, novel) in allNovels.withIndex()) {
            if (stopSignal.get()) {
                emit(FetchEvent.Log("> ⏹ user stop at ch ${idx + 1}/$total · 已抓 ${chapters.size} 章会写成截短版"))
                break
            }
            val cPos = idx + 1
            emit(FetchEvent.Networking(pageIndex = cPos, endpoint = "/v1/novel/text?id=${novel.id}"))
            val t0 = System.currentTimeMillis()
            try {
                val wNovel = fetchChapterWebNovel(novel)
                val chapter = MergedChapter.numbered(
                    position = cPos,
                    rawTitle = novel.title.orEmpty(),
                    text = DownloadNovelTask.replaceBrWithNewLine(wNovel.text),
                    webNovel = wNovel,
                )
                chapters += chapter
                emit(FetchEvent.ChapterMerged(
                    chapterIndex = cPos,
                    totalChapters = total,
                    title = novel.title.orEmpty(),
                    latencyMs = System.currentTimeMillis() - t0,
                ))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.e(e, "merge-novel-series: chapter ${novel.id} failed")
                skipped++
                emit(FetchEvent.Warning("ch $cPos/$total (id=${novel.id}) failed: ${e.message ?: e::class.java.simpleName}"))
            }
            if (cPos < total && !stopSignal.get()) {
                emit(FetchEvent.RateLimit(CHAPTER_DELAY_MS))
                delay(CHAPTER_DELAY_MS)
            }
        }

        if (chapters.isEmpty()) {
            // 区分:用户提前 cancel 没抓到任何东西 vs 全部章节都失败
            val msg = if (stopSignal.get()) {
                "用户停止 · 还没来得及抓任何章节"
            } else {
                "全部 $total 章都抓取失败,无法写文件"
            }
            emit(FetchEvent.Errored(msg, 0))
            return@flow
        }

        // ── 2.5) 默认 TXT + 开启「有插图时存 EPUB」：检测到插图后交给 UI 质询 ──
        var effectiveFormat = format
        if (allowAutoEpub && format == ExportFormat.Txt &&
            Shaft.sSettings.isDefaultNovelExportEpubOnImages &&
            chapters.any { it.webNovel?.let(::hasIllustrations) == true }
        ) {
            emit(FetchEvent.Log("> 检测到插图，询问导出格式…"))
            val chosen = confirmFormat?.invoke(format)
            if (chosen == null) {
                emit(FetchEvent.Log("> 用户取消 · 未生成文件"))
                emit(FetchEvent.Errored("用户取消 · 未生成文件", chapters.size))
                return@flow
            }
            effectiveFormat = chosen
        }

        // ── 3) 写文件 ──
        // 目录 + 文件名全部由「小说系列 · 合并下载」模板渲染（issue #964）。章节数
        // 报实抓到的那个：用户中途停止时产物就是截短版，文件名不该谎报全集。
        val destination = DownloadItems.novelSeriesMergeDestination(
            seriesDetail = seriesDetail,
            chapterCount = chapters.size,
            ext = effectiveFormat.extension,
            r18 = allNovels.any { (it.x_restrict ?: 0) > 0 },
        )
        val mergeName = destination.filename
        emit(FetchEvent.WritingFile(mergeName))

        val content = MergedNovelContent(
            displayTitle = seriesDetail.title.orEmpty(),
            author = seriesDetail.user?.name,
            sourceUrl = "https://www.pixiv.net/novel/series/$seriesId",
            caption = seriesDetail.caption,
            chapters = chapters,
            documentId = "novel_series_$seriesId",
        )
        val writer = MergedNovelWriters.forFormat(effectiveFormat)
        val ok = writer.write(ctx, content, destination) { key, bytes ->
            emit(FetchEvent.ImageFetched(key, bytes))
        }
        if (!ok) {
            emit(FetchEvent.Errored("写入文件失败 ($mergeName)", chapters.size))
            return@flow
        }

        // ── 4) 终态 ──
        if (skipped > 0) {
            emit(FetchEvent.Log("  ⚠ 跳过 $skipped 章 (网络/解析失败)"))
        }
        if (stopSignal.get()) {
            emit(FetchEvent.Log("  ▸ 用户停止 · 截短版 (${chapters.size}/$total 章)"))
        }
        emit(FetchEvent.Log("  ▸ 文件: $mergeName"))
        emit(FetchEvent.Done(
            total = chapters.size,
            elapsedMs = System.currentTimeMillis() - startedAt,
            pageCount = chapters.size,
        ))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchAllNovels(
        seriesId: Long,
        initial: List<Novel>,
        emit: suspend (FetchEvent) -> Unit,
    ): List<Novel> {
        val all = initial.toMutableList()
        var lastOrder: Int? = if (all.isEmpty()) null else all.size
        var safetyGuard = 0
        var pageIdx = 0
        while (safetyGuard < 50) {
            safetyGuard++
            pageIdx++
            emit(FetchEvent.Networking(pageIdx, "/v1/novel/series?series_id=$seriesId&last_order=${lastOrder ?: "—"}"))
            val t0 = System.currentTimeMillis()
            val resp = try {
                Client.appApi.getNovelSeries(seriesId, lastOrder)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                emit(FetchEvent.Warning("getNovelSeries lastOrder=$lastOrder failed: ${ex.message}"))
                break
            }
            val latency = System.currentTimeMillis() - t0
            val page = resp.novels.orEmpty()
            emit(FetchEvent.PageReceived(pageIdx, page.size, latency, all.size + page.size))
            if (page.isEmpty()) break
            val existingIds = all.map { it.id }.toHashSet()
            val fresh = page.filter { it.id !in existingIds }
            if (fresh.isEmpty()) break
            all.addAll(fresh)
            if (resp.next_url.isNullOrEmpty()) break
            lastOrder = all.size
            delay(SERIES_PAGE_DELAY_MS)
        }
        return all
    }

    /**
     * 拉一章 Web 端 HTML 并解出 [WebNovel]。caller 负责再走 [DownloadNovelTask.replaceBrWithNewLine]
     * 拿正文,WebNovel 整体留着是为了让 EPUB writer 能从 `illusts` / `images` 图表
     * 里把 `[pixivimage:XXX]` / `[uploadedimage:XXX]` 解析成真实 URL。
     */
    private suspend fun fetchChapterWebNovel(novel: Novel): WebNovel {
        val html = Client.appApi.getNovelText(novel.id).string()
        return WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel: ${novel.id}")
    }

    private companion object {
        const val CHAPTER_DELAY_MS = 1500L
        const val SERIES_PAGE_DELAY_MS = 1000L
    }
}

private fun hasIllustrations(webNovel: WebNovel): Boolean =
    !webNovel.illusts.isNullOrEmpty() || !webNovel.images.isNullOrEmpty()
