package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.models.NovelSeriesItem
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 跨系列批量下载：由 FragmentNovelSeries（某作者的小说系列总览）的顶部
 * 下载按钮驱动，支持三种模式——
 *
 *  1. PerSeries:            选中若干系列 / 全部系列，**每个系列各自合并为一个独立 txt**。
 *  2. AllSeriesMergedOne:   所有系列的全部章节合并为**唯一一个 txt**。
 *
 * 复用 [MergeDownloadNovelSeriesTask] 里的合并格式思路：信息头 + 分章节块。
 * 抓章节失败不中断整个批次，仅计数。
 */
object CrossSeriesDownloadTask {

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
        onFinished: (success: Int, failures: List<SeriesFailure>) -> Unit,
    ) {
        if (seriesList.isEmpty()) {
            onFinished(0, emptyList())
            return
        }
        val ctx = Shaft.getContext()
        activity.lifecycleScope.launch {
            val failures = mutableListOf<SeriesFailure>()
            var successCount = 0
            seriesList.forEachIndexed { index, seriesItem ->
                val title = seriesItem.title.orEmpty()
                val pos = index + 1
                ToastUtils.show(
                    ctx.getString(
                        R.string.cross_series_download_starting_series,
                        pos, seriesList.size, title,
                    )
                )
                try {
                    withContext(Dispatchers.IO) {
                        downloadOneSeriesToSingleFile(seriesItem)
                    }
                    successCount++
                    ToastUtils.show(
                        ctx.getString(R.string.cross_series_download_series_ok, title)
                    )
                } catch (ex: Exception) {
                    Timber.e(ex, "CrossSeriesDownloadTask: series ${seriesItem.id} failed")
                    failures += SeriesFailure(
                        seriesTitle = title,
                        reason = ex.message ?: ex::class.java.simpleName,
                    )
                    ToastUtils.show(
                        ctx.getString(
                            R.string.cross_series_download_series_failed,
                            title, ex.message ?: ""
                        )
                    )
                }
                if (pos < seriesList.size) delay(1500L)
            }
            ToastUtils.show(
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
        onFinished: (ok: Boolean, skippedChapters: Int) -> Unit,
    ) {
        if (seriesList.isEmpty()) {
            onFinished(false, 0)
            return
        }
        val ctx = Shaft.getContext()
        activity.lifecycleScope.launch {
            try {
                val lineSep = "\n"
                // 顶层信息头（作者 + 系列数量）
                val header = buildString {
                    append("===== 作者 ").append(authorName.orEmpty()).append(" 的小说系列合集 =====")
                    append(lineSep)
                    append("User:").append(authorName.orEmpty())
                        .append("(https://www.pixiv.net/users/").append(authorId).append(")")
                    append(lineSep)
                    append("系列数：").append(seriesList.size).append(lineSep)
                    append("======================").append(lineSep).append(lineSep)
                }

                val out = StringBuilder(header)
                var skippedChapters = 0

                seriesList.forEachIndexed { sIdx, seriesItem ->
                    val sPos = sIdx + 1
                    val allNovels = try {
                        withContext(Dispatchers.IO) {
                            fetchAllNovels(seriesItem.id.toLong())
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "fetchAllNovels failed for series ${seriesItem.id}")
                        emptyList()
                    }

                    // 系列块信息头
                    out.append(lineSep).append(lineSep)
                    out.append("<<<<< 系列 ").append(sPos).append("/").append(seriesList.size)
                        .append(" 《").append(seriesItem.title.orEmpty()).append("》 >>>>>")
                    out.append(lineSep)
                    out.append("SeriesId:").append(seriesItem.id).append(lineSep)
                    out.append("Chapters:").append(allNovels.size).append(lineSep)
                    out.append(lineSep)

                    allNovels.forEachIndexed { cIdx, novel ->
                        val cPos = cIdx + 1
                        ToastUtils.show(
                            ctx.getString(
                                R.string.cross_series_download_merge_progress,
                                sPos, seriesList.size, cPos, allNovels.size,
                            )
                        )
                        try {
                            val body = withContext(Dispatchers.IO) {
                                fetchChapterBody(novel, cPos)
                            }
                            out.append(body).append(lineSep)
                        } catch (ex: Exception) {
                            Timber.e(ex, "chapter ${novel.id} failed (series ${seriesItem.id})")
                            skippedChapters++
                        }
                        // Pixiv rate-limit friendly, same as existing tasks
                        delay(1500L)
                    }
                }

                val fileName = buildMergedFileName(authorName, authorId)
                val ok = withContext(Dispatchers.IO) {
                    saveToDownloadsScopedStorage(ctx, fileName, out.toString())
                }
                if (ok) {
                    ToastUtils.show(
                        ctx.getString(R.string.cross_series_download_merge_finished, fileName)
                    )
                } else {
                    ToastUtils.show(
                        ctx.getString(R.string.cross_series_download_merge_failed_save)
                    )
                }
                onFinished(ok, skippedChapters)
            } catch (ex: Exception) {
                Timber.e(ex, "CrossSeriesDownloadTask.runAllMergedOne failed")
                ToastUtils.show(ex.message ?: ex::class.java.simpleName)
                onFinished(false, -1)
            }
        }
    }

    // ── 私有实现 ─────────────────────────────────────────────

    /**
     * 复用 [MergeDownloadNovelSeriesTask] 的路子：对一个 series，抓全部章节，
     * 合并后写一个文件。直接调用已有的 task，避免重复实现同一格式。
     */
    private suspend fun downloadOneSeriesToSingleFile(seriesItem: NovelSeriesItem) {
        val seriesId = seriesItem.id.toLong()
        // 先拉一次 getNovelSeries 拿 detail（带 user / caption 等），然后翻页。
        val initial = Client.appApi.getNovelSeries(seriesId)
        val detail = initial.novel_series_detail
            ?: throw RuntimeException("no series detail for $seriesId")
        val allNovels = fetchAllNovelsStartingFrom(seriesId, initial.novels.orEmpty())

        if (allNovels.isEmpty()) {
            throw RuntimeException("series ${seriesItem.title} has no chapters")
        }

        val lineSep = "\n"
        val header = buildString {
            append("《").append(detail.title.orEmpty()).append("》").append(lineSep)
            detail.user?.let { u ->
                append("Name:").append(u.name.orEmpty())
                    .append("(https://www.pixiv.net/users/").append(u.id).append(")")
                    .append(lineSep)
            }
            append("Source:https://www.pixiv.net/novel/series/").append(detail.id).append(lineSep)
            val caption = detail.caption.orEmpty().trim()
            if (caption.isNotEmpty()) {
                append("Caption:").append(lineSep).append(caption).append(lineSep)
            }
            append(lineSep)
            append("----------------------").append(lineSep).append(lineSep).append(lineSep)
        }

        val body = StringBuilder()
        allNovels.forEachIndexed { index, novel ->
            val cPos = index + 1
            try {
                body.append(fetchChapterBody(novel, cPos)).append(lineSep)
            } catch (ex: Exception) {
                Timber.e(ex, "chapter ${novel.id} failed in PerSeries mode")
                // swallow — the file still saves, just with fewer chapters
            }
            if (cPos < allNovels.size) delay(1500L)
        }

        val ctx = Shaft.getContext()
        val fileName = buildPerSeriesFileName(detail.title.orEmpty(), detail.id)
        val content = header + body.toString()
        val ok = saveToDownloadsScopedStorage(ctx, fileName, content)
        if (!ok) throw RuntimeException("saveToDownloadsScopedStorage returned false")
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

    private suspend fun fetchChapterBody(novel: Novel, seriesIndex: Int): String {
        val html = Client.appApi.getNovelText(novel.id).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel: ${novel.id}")
        val chapterTitle = "第${seriesIndex}篇•" + truncate(novel.title.orEmpty(), 30)
        val lineSep = "\n"
        return buildString {
            append(lineSep).append(lineSep)
            append("<===== ").append(chapterTitle).append(" =====>")
            append(lineSep).append(lineSep)
            append(DownloadNovelTask.replaceBrWithNewLine(wNovel.text))
            append(lineSep).append(lineSep)
        }
    }

    private fun truncate(input: String, max: Int): String {
        return if (input.length <= max) input else input.substring(0, max)
    }

    private fun buildPerSeriesFileName(rawTitle: String, seriesId: Long): String {
        val sanitized = rawTitle.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
        val base = if (sanitized.isEmpty()) "novel_series_$seriesId" else sanitized
        return "${base}_合集_ID${seriesId}.txt"
    }

    private fun buildMergedFileName(authorName: String?, authorId: Int): String {
        val sanitized = authorName.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
        val base = if (sanitized.isEmpty()) "user_$authorId" else sanitized
        return "${base}_全系列合集_U${authorId}.txt"
    }
}
