package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 合并下载：把一个系列的全部章节抓下来合并成一个 TXT 文件。
 *
 * 恢复自 [ceui.lisa.fragments.FragmentNovelSeriesDetail.batch_download_as_one]
 * 旧实现（Java + RxJava 版），用 coroutine + 现有下载基础设施重写。单个章节
 * 抓取失败时跳过、不中断整个任务 —— 原 Java 版一旦某一章 parse 失败整批就 NPE。
 *
 * UI 入口：NovelSeriesFragment 右下「下载」按钮 → SeriesDownloadOptionsSheet
 * 的「合并下载」选项。
 */
class MergeDownloadNovelSeriesTask(
    private val activity: FragmentActivity,
    private val seriesDetail: NovelSeriesDetail,
    private val knownNovels: List<Novel>,
    private val onFinished: (ok: Boolean, failedCount: Int) -> Unit,
) {

    init {
        start()
    }

    private fun start() {
        activity.lifecycleScope.launch {
            val ctx = Shaft.getContext()
            try {
                // 1) 先把系列所有章节列表拉完（NovelSeriesFragment 的 VM 可能没翻到底，
                //    比如用户刚打开就点了合并下载）。有 next_url 就继续翻页。
                val allNovels = withContext(Dispatchers.IO) { fetchAllNovels(seriesDetail.id, knownNovels) }
                if (allNovels.isEmpty()) {
                    ToastUtils.show(ctx.getString(R.string.merge_download_failed_empty))
                    onFinished(false, 0)
                    return@launch
                }

                val total = allNovels.size
                ToastUtils.show(ctx.getString(R.string.merge_download_preparing, total))

                // 2) 构造系列信息头（模仿原 Java 版格式）
                val lineSep = "\n"
                val header = buildString {
                    append("《").append(seriesDetail.title.orEmpty()).append("》").append(lineSep)
                    seriesDetail.user?.let { u ->
                        append("Name:").append(u.name.orEmpty())
                            .append("(https://www.pixiv.net/users/").append(u.id).append(")")
                            .append(lineSep)
                    }
                    append("Source:https://www.pixiv.net/novel/series/").append(seriesDetail.id).append(lineSep)
                    val caption = seriesDetail.caption.orEmpty().trim()
                    if (caption.isNotEmpty()) {
                        append("Caption:").append(lineSep).append(caption).append(lineSep)
                    }
                    append(lineSep)
                    append("----------------------").append(lineSep).append(lineSep).append(lineSep)
                }

                // 3) 顺序抓每一章的正文（失败跳过）
                val chapterBodies = StringBuilder()
                var failedCount = 0
                allNovels.forEachIndexed { index, novel ->
                    val done = index + 1
                    ToastUtils.show(ctx.getString(R.string.merge_download_progress, done, total))
                    try {
                        val body = withContext(Dispatchers.IO) { fetchChapterBody(novel, done) }
                        chapterBodies.append(body).append(lineSep)
                    } catch (ex: Exception) {
                        Timber.e(ex, "MergeDownloadNovelSeriesTask: chapter ${novel.id} failed")
                        failedCount++
                    }
                    if (done < total) delay(1500L)
                }

                // 4) 写入文件
                val fileName = buildMergeFileName(seriesDetail)
                val content = header + chapterBodies.toString()
                val ok = withContext(Dispatchers.IO) {
                    saveToDownloadsScopedStorage(ctx, fileName, content)
                }
                if (!ok) {
                    ToastUtils.show(ctx.getString(R.string.merge_download_failed_save))
                    onFinished(false, failedCount)
                    return@launch
                }

                if (failedCount > 0) {
                    ToastUtils.show(ctx.getString(R.string.merge_download_some_chapters_failed, failedCount))
                } else {
                    ToastUtils.show(ctx.getString(R.string.merge_download_finished, fileName))
                }
                onFinished(true, failedCount)
            } catch (ex: Exception) {
                Timber.e(ex, "MergeDownloadNovelSeriesTask failed")
                ToastUtils.show(ex.message ?: ex::class.java.simpleName)
                onFinished(false, -1)
            }
        }
    }

    private suspend fun fetchAllNovels(seriesId: Long, initial: List<Novel>): List<Novel> {
        val all = initial.toMutableList()
        var lastOrder: Int? = if (all.isEmpty()) null else all.size
        // 如果初始列表为空或可能不完整，主动翻页。直到接口返回空或 next_url 为空。
        // getNovelSeries 的分页是通过 last_order 驱动的，这里沿用 VM 里的做法。
        var safetyGuard = 0
        while (safetyGuard < 50) {
            safetyGuard++
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

    private fun buildMergeFileName(detail: NovelSeriesDetail): String {
        val raw = detail.title.orEmpty()
        val sanitized = raw.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
        val base = if (sanitized.isEmpty()) "novel_series_${detail.id}" else sanitized
        return "${base}_合集_ID${detail.id}.txt"
    }
}
