package ceui.pixiv.ui.task

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.models.NovelSeriesItem
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.MergedChapter
import ceui.pixiv.ui.novel.reader.export.MergedNovelContent
import ceui.pixiv.ui.novel.reader.export.MergedNovelWriters
import com.hjq.toast.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 跨系列批量下载：由 FragmentNovelSeries（某作者的小说系列总览）的顶部
 * 下载按钮驱动，支持三种模式——
 *
 *  1. PerSeries:            选中若干系列 / 全部系列，**每个系列各自合并为一个独立文件**。
 *  2. AllSeriesMergedOne:   所有系列的全部章节合并为**唯一一个文件**。
 *
 * 输出格式由调用方传入的 [ExportFormat] 决定（TXT/MD/PDF/EPUB），实际落盘
 * 走 [MergedNovelWriters]。抓章节失败不中断整个批次，仅计数。
 */
class CrossSeriesDownloadTask(context: Context) {

    /** 只留 applicationContext：写文件 / 取字符串都在 lifecycleScope 协程里跑，不能拿 Activity。 */
    private val ctx: Context = context.applicationContext

    enum class Mode {
        /** 选中的系列，每个各自合并为一个文件。 */
        PerSeriesSelected,

        /** 全部系列，每个各自合并为一个文件。 */
        PerSeriesAll,

        /** 全部系列合并为一个文件。 */
        AllMergedOne,
    }

    data class SeriesFailure(val seriesTitle: String, val reason: String)

    /**
     * PerSeries 模式：对给定的 [seriesList] 每一项各抓一份合并文件。
     * 完成后调用 [onFinished]，传入成功 / 失败统计。
     */
    fun runPerSeries(
        activity: FragmentActivity,
        seriesList: List<NovelSeriesItem>,
        format: ExportFormat,
        onFinished: (success: Int, failures: List<SeriesFailure>) -> Unit,
    ) {
        if (seriesList.isEmpty()) {
            onFinished(0, emptyList())
            return
        }
        activity.lifecycleScope.launch {
            val failures = mutableListOf<SeriesFailure>()
            var successCount = 0
            seriesList.forEachIndexed { index, seriesItem ->
                val title = seriesItem.title.orEmpty()
                val pos = index + 1
                Toaster.show(
                    ctx.getString(
                        R.string.cross_series_download_starting_series,
                        pos, seriesList.size, title,
                    )
                )
                try {
                    withContext(Dispatchers.IO) {
                        downloadOneSeriesToSingleFile(seriesItem, format)
                    }
                    successCount++
                    Toaster.show(
                        ctx.getString(R.string.cross_series_download_series_ok, title)
                    )
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "CrossSeriesDownloadTask: series ${seriesItem.id} failed")
                    failures += SeriesFailure(
                        seriesTitle = title,
                        reason = ex.message ?: ex::class.java.simpleName,
                    )
                    Toaster.show(
                        ctx.getString(
                            R.string.cross_series_download_series_failed,
                            title, ex.message ?: ""
                        )
                    )
                }
                if (pos < seriesList.size) delay(1500L)
            }
            Toaster.show(
                ctx.getString(
                    R.string.cross_series_download_all_done,
                    successCount, failures.size,
                )
            )
            onFinished(successCount, failures)
        }
    }

    /**
     * AllMergedOne 模式：把 [seriesList] 里所有系列的全部章节合并为**一个**文件，
     * 以作者名命名。章节抓取失败跳过，最终 [onFinished] 返回是否写入成功 +
     * 跳过章节数。
     */
    fun runAllMergedOne(
        activity: FragmentActivity,
        seriesList: List<NovelSeriesItem>,
        authorName: String?,
        authorId: Int,
        format: ExportFormat,
        onFinished: (ok: Boolean, skippedChapters: Int) -> Unit,
    ) {
        if (seriesList.isEmpty()) {
            onFinished(false, 0)
            return
        }
        activity.lifecycleScope.launch {
            try {
                val chapters = mutableListOf<MergedChapter>()
                var skippedChapters = 0
                // chapters 里还混着每个系列的分隔头，单独计一份真实章节数喂给
                // 文件名模板的 {chapters}。
                var mergedChapters = 0

                seriesList.forEachIndexed { sIdx, seriesItem ->
                    val sPos = sIdx + 1
                    val allNovels = try {
                        withContext(Dispatchers.IO) {
                            fetchAllNovels(seriesItem.id.toLong())
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "fetchAllNovels failed for series ${seriesItem.id}")
                        emptyList()
                    }

                    // 用一个 chapter 当 series 分隔头（用户在 reader 里能看到目录分级）
                    chapters += MergedChapter(
                        title = "<系列 $sPos/${seriesList.size}>《${seriesItem.title.orEmpty()}》",
                        text = "SeriesId: ${seriesItem.id}\nChapters: ${allNovels.size}",
                    )

                    allNovels.forEachIndexed { cIdx, novel ->
                        val cPos = cIdx + 1
                        Toaster.show(
                            ctx.getString(
                                R.string.cross_series_download_merge_progress,
                                sPos, seriesList.size, cPos, allNovels.size,
                            )
                        )
                        try {
                            val wNovel = withContext(Dispatchers.IO) {
                                fetchChapterWebNovel(novel)
                            }
                            chapters += MergedChapter.numbered(
                                position = cPos,
                                rawTitle = novel.title.orEmpty(),
                                text = DownloadNovelTask.replaceBrWithNewLine(wNovel.text),
                                webNovel = wNovel,
                            )
                            mergedChapters++
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.e(ex, "chapter ${novel.id} failed (series ${seriesItem.id})")
                            skippedChapters++
                        }
                        // Pixiv rate-limit friendly, same as existing tasks
                        delay(1500L)
                    }
                }

                val mergeName = buildMergedFileName(authorName, authorId, format)
                // 这一份不属于任何系列，模板只出目录，文件名仍由这里定。
                val destination = DownloadItems.novelSeriesMergeDestinationForAuthor(
                    authorId = authorId,
                    authorName = authorName,
                    chapterCount = mergedChapters,
                    ext = format.extension,
                    mergeFileName = mergeName,
                )
                val content = MergedNovelContent(
                    displayTitle = "${authorName.orEmpty()} 全系列合集",
                    author = authorName,
                    sourceUrl = "https://www.pixiv.net/users/$authorId",
                    caption = ctx.getString(
                        R.string.cross_series_download_caption,
                        seriesList.size,
                    ),
                    chapters = chapters,
                    documentId = "pixiv_author_${authorId}_merged",
                )
                val writer = MergedNovelWriters.forFormat(format)
                val ok = withContext(Dispatchers.IO) { writer.write(ctx, content, destination) }
                if (ok) {
                    Toaster.show(
                        ctx.getString(R.string.cross_series_download_merge_finished, destination.filename)
                    )
                } else {
                    Toaster.show(
                        ctx.getString(R.string.cross_series_download_merge_failed_save)
                    )
                }
                onFinished(ok, skippedChapters)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "CrossSeriesDownloadTask.runAllMergedOne failed")
                Toaster.show(ex.message ?: ex::class.java.simpleName)
                onFinished(false, -1)
            }
        }
    }

    // ── 私有实现 ─────────────────────────────────────────────

    /**
     * 复用 [MergeDownloadNovelSeriesTask] 的路子：对一个 series，抓全部章节，
     * 合并后写一个文件。这里独立实现是因为 cross-series 入口已经在
     * NovelSeriesItem 维度迭代，没走 NovelSeriesDetail VM。
     */
    private suspend fun downloadOneSeriesToSingleFile(
        seriesItem: NovelSeriesItem,
        format: ExportFormat,
    ) {
        val seriesId = seriesItem.id.toLong()
        // 先拉一次 getNovelSeries 拿 detail（带 user / caption 等），然后翻页。
        val initial = Client.appApi.getNovelSeries(seriesId)
        val detail = initial.novel_series_detail
            ?: throw RuntimeException("no series detail for $seriesId")
        val allNovels = fetchAllNovelsStartingFrom(seriesId, initial.novels.orEmpty())

        if (allNovels.isEmpty()) {
            throw RuntimeException("series ${seriesItem.title} has no chapters")
        }

        val chapters = mutableListOf<MergedChapter>()
        allNovels.forEachIndexed { index, novel ->
            val cPos = index + 1
            try {
                val wNovel = fetchChapterWebNovel(novel)
                chapters += MergedChapter.numbered(
                    position = cPos,
                    rawTitle = novel.title.orEmpty(),
                    text = DownloadNovelTask.replaceBrWithNewLine(wNovel.text),
                    webNovel = wNovel,
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "chapter ${novel.id} failed in PerSeries mode")
                // swallow — the file still saves, just with fewer chapters
            }
            if (cPos < allNovels.size) delay(1500L)
        }

        // 目录 + 文件名都走「小说系列 · 合并下载」模板；抓失败的章节不算进
        // {chapters}，文件名如实反映这份合集里到底有几章。
        val destination = DownloadItems.novelSeriesMergeDestination(
            seriesDetail = detail,
            chapterCount = chapters.size,
            ext = format.extension,
            r18 = allNovels.any { (it.x_restrict ?: 0) > 0 },
        )
        val content = MergedNovelContent(
            displayTitle = detail.title.orEmpty(),
            author = detail.user?.name,
            sourceUrl = "https://www.pixiv.net/novel/series/${detail.id}",
            caption = detail.caption,
            chapters = chapters,
            documentId = "novel_series_${detail.id}",
        )
        val writer = MergedNovelWriters.forFormat(format)
        val ok = writer.write(ctx, content, destination)
        if (!ok) throw RuntimeException("writer.write returned false")
    }

    /**
     * 从系列 id 主动拉完全部章节（给 AllMergedOne 用）。
     */
    private suspend fun fetchAllNovels(seriesId: Long): List<Novel> {
        val initial = Client.appApi.getNovelSeries(seriesId)
        return fetchAllNovelsStartingFrom(seriesId, initial.novels.orEmpty())
    }

    private suspend fun fetchAllNovelsStartingFrom(
        seriesId: Long,
        initial: List<Novel>,
    ): List<Novel> {
        val all = initial.toMutableList()
        var lastOrder: Int? = if (all.isEmpty()) null else all.size
        var safety = 0
        while (safety < 50) {
            safety++
            val resp = try {
                Client.appApi.getNovelSeries(seriesId, lastOrder)
            } catch (ex: Exception) {
                Timber.e(ex, "getNovelSeries pagination failed at lastOrder=$lastOrder")
                break
            }
            val page = resp.novels.orEmpty()
            if (page.isEmpty()) break
            val existingIds = all.map { it.id }.toHashSet()
            val fresh = page.filter { it.id !in existingIds }
            if (fresh.isEmpty()) break
            all.addAll(fresh)
            if (resp.next_url.isNullOrEmpty()) break
            lastOrder = all.size
            delay(1000L)
        }
        return all
    }

    /** 同 [MergeDownloadNovelSeriesTask.fetchChapterWebNovel]:整体留 WebNovel 让 EPUB 拿到 illusts/images。 */
    private suspend fun fetchChapterWebNovel(novel: Novel): WebNovel {
        val html = Client.appApi.getNovelText(novel.id).string()
        return WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel: ${novel.id}")
    }

    private fun buildMergedFileName(
        authorName: String?,
        authorId: Int,
        format: ExportFormat,
    ): String {
        val sanitized = authorName.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
        val base = if (sanitized.isEmpty()) "user_$authorId" else sanitized
        return "${base}_全系列合集_U${authorId}.${format.extension}"
    }
}
