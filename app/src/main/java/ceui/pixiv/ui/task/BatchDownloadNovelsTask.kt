package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.ui.common.getTxtFileIdInDownloads
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import ceui.pixiv.download.config.DownloadItems
import com.hjq.toast.ToastUtils
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
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    private val onFinished: (failures: List<FailedNovel>) -> Unit,
    /**
     * 当 [novels] 是「同一个系列的章节按系列顺序」（NovelSeriesFragment 走的就是
     * 这条路径）时设为 true，下载时把 1-based 位置 + 总数交给 [NovelHeaderRenderer]，
     * 用户在「信息头设置」勾选「本篇在系列中的序号」就能看到「第 X 章 / 共 Y 章」。
     *
     * 「未归类作品」之类不是同一系列的批量场景保持 false（默认），
     * 这种情况下 NovelHeaderRenderer 自身也会因为 isSeriesChapter=false 而跳过该字段。
     *
     * 修复 issue #710：作者在 Pixiv 上手工重排过章节后，正文里写死的 `[chapter:N]`
     * 标签序号会和系列顺序对不上；启用本字段后用户能看到无歧义的位置。
     */
    private val orderIsSeriesPosition: Boolean = false,
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
                        downloadOne(novel, seriesIndex = if (orderIsSeriesPosition) done else null)
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "BatchDownloadNovelsTask: failed on ${novel.id} (${novel.title})")
                    failures += FailedNovel(novel, ex.message ?: ex::class.java.simpleName)
                }
                onProgress(done, total)
                ToastUtils.show(ctx.getString(R.string.batch_download_progress, done, total))
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
        val fileName = DownloadItems.novelFileNameFromLoxia(novel)

        // Skip already-downloaded files. DownloadNovelTask uses the same
        // pre-check before it hits the network.
        if (getTxtFileIdInDownloads(ctx, fileName) != null) {
            Timber.d("$fileName already exists, skipping")
            return
        }

        val html = Client.appApi.getNovelText(novel.id).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel
            ?: throw RuntimeException("invalid web novel")

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
                    seriesTotal = if (seriesIndex != null) novels.size else null,
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

        val ok = saveToDownloadsScopedStorage(ctx, fileName, buffer.toString())
        if (!ok) {
            throw RuntimeException("saveToDownloadsScopedStorage returned false")
        }
    }
}
