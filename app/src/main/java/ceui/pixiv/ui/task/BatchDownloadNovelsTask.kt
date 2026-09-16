package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.pixiv.api.Client
import ceui.loxia.Novel
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.ui.common.getTxtFileIdInDownloads
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import com.hjq.toast.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Per-novel failure info reported to the caller. `reason` is a short human
 * readable message surfaced directly in the failure dialog on the series
 * page, so keep it short.
 */
data class FailedNovel(
    val novel: Novel,
    val reason: String?,
)

/**
 * Downloads a list of novels sequentially. Each failure is swallowed into
 * [FailedNovel] so one bad novel does not abort the whole batch (this was
 * the user complaint on the "下载合集" path — a single parse error would
 * kill the run with no recovery).
 *
 * Progress is surfaced both via the [onProgress] callback and as a toast
 * ("下载中 done/total"). When the batch finishes, [onFinished] receives the
 * list of failures (empty == all OK).
 */
class BatchDownloadNovelsTask(
    private val activity: FragmentActivity,
    private val novels: List<Novel>,
    private val format: ExportFormat,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val onFinished: (failures: List<FailedNovel>) -> Unit,
    /**
     * novelId → 本篇在**系列**中的 1-based 位置（不是在 [novels] 里的位置——
     * 多选下载时两者不同：勾选第 3、5、9 章，`novels` 里的下标是 1、2、3，
     * 而系列位置必须还是 3、5、9）。由调用方按系列完整顺序计算。
     *
     * 位置 + [seriesTotal] 同时喂给 [NovelHeaderRenderer]（「第 X / Y 篇」，
     * issue #710）和路径模板的 `{series_order}` 变量（issue #964）。
     *
     * 「未归类作品」之类不是同一系列的批量场景保持 null（默认），
     * 这种情况下 NovelHeaderRenderer 自身也会因为 isSeriesChapter=false 而跳过该字段。
     */
    private val seriesPositions: Map<Long, Int>? = null,
    /** 系列总篇数（不是 [novels].size——多选时后者只是选中数）。 */
    private val seriesTotal: Int? = null,
) {

    init {
        start()
    }

    private fun start() {
        val total = novels.size
        if (total == 0) {
            onFinished(emptyList())
            return
        }

        activity.lifecycleScope.launch {
            val failures = mutableListOf<FailedNovel>()
            val ctx = Shaft.getContext()

            novels.forEachIndexed { index, novel ->
                val done = index + 1
                try {
                    withContext(Dispatchers.IO) {
                        downloadOne(novel, seriesIndex = seriesPositions?.get(novel.id))
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "BatchDownloadNovelsTask: failed on ${novel.id} (${novel.title})")
                    failures += FailedNovel(novel, ex.message ?: ex::class.java.simpleName)
                }
                onProgress(done, total)
                Toaster.show(ctx.getString(R.string.batch_download_progress, done, total))
                // Match DownloadNovelTask's own delays — Pixiv is quick to
                // 429 if we hammer getNovelText back to back.
                if (done < total) delay(1500L)
            }

            onFinished(failures)
        }
    }

    /**
     * Mirrors [DownloadNovelTask.execute]'s core persistence path without
     * the QueuedRunnable/TaskStatus plumbing (we don't need per-novel
     * progress UI here — we drive the batch UI ourselves). Any exception
     * bubbles up and becomes a [FailedNovel].
     */
    private suspend fun downloadOne(novel: Novel, seriesIndex: Int?) {
        val ctx = Shaft.getContext()
        // 序号 / 总数同时喂给路径模板（{series_order}，issue #964）和下方的信息头。
        val total = seriesTotal.takeIf { seriesIndex != null }
        val destination: RelativePath = DownloadItems.novelDestinationFromLoxia(
            novel,
            extOverride = format.extension,
            seriesOrder = seriesIndex,
            seriesTotal = total,
        )
        val fileName = destination.filename

        // Skip already-downloaded files. DownloadNovelTask uses the same
        // pre-check before it hits the network.
        if (format == ExportFormat.Txt && getTxtFileIdInDownloads(ctx, fileName) != null) {
            Timber.d("$fileName already exists, skipping")
            return
        }

        val html = Client.appApi.getNovelText(novel.id).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel")

        // 非 TXT 格式直接复用阅读器导出链路，避免在批量下载里再维护一套 MD/EPUB/PDF 实现。
        if (format != ExportFormat.Txt) {
            val tokens = ContentParser.tokenize(wNovel)
            when (val result = NovelExportManager.export(
                context = ctx,
                format = format,
                novel = novel,
                webNovel = wNovel,
                tokens = tokens,
                seriesOrder = seriesIndex,
                seriesTotal = total,
            )) {
                is ExportResult.Success -> Unit
                is ExportResult.Failure -> throw RuntimeException(result.message)
            }
            return
        }

        val buffer = StringBuffer().apply {
            append("\n\n")
            append("<===== Shaft Novel Start =====>")
            append("\n\n")
            append(
                NovelHeaderRenderer.render(
                    novel = novel,
                    preset = HeaderConfigRepo.activePreset(),
                    isSeriesChapter = novel.series != null,
                    seriesIndex = seriesIndex,
                    seriesTotal = total,
                )
            )
            append("\n")
            append("正文：")
            append("\n\n")
            append(DownloadNovelTask.replaceBrWithNewLine(wNovel.text))
            append("\n\n")
            append("<===== Shaft Novel End =====>")
            append("\n\n")
        }

        val ok = saveToDownloadsScopedStorage(ctx, destination, buffer.toString())
        if (!ok) {
            throw RuntimeException("saveToDownloadsScopedStorage returned false")
        }
    }
}
