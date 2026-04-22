package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.ui.common.getTxtFileIdInDownloads
import ceui.pixiv.ui.common.saveToDownloadsScopedStorage
import ceui.pixiv.ui.works.buildPixivNovelFileName
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
                        downloadOne(novel)
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
    private suspend fun downloadOne(novel: Novel) {
        val ctx = Shaft.getContext()
        val fileName = buildPixivNovelFileName(novel)

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
            append("标题：${novel.title}")
            append("\n\n")
            append("作者：${novel.user?.name}")
            append("\n\n")
            append("作者链接：https://www.pixiv.net/users/${novel.user?.id}")
            append("\n\n")
            append("小说链接：https://www.pixiv.net/novel/show.php?id=${novel.id}")
            append("\n\n")
            append("标签：${novel.tags?.map { it.name }?.joinToString(", ")}")
            append("\n\n")
            append("简介：${DownloadNovelTask.replaceBrWithNewLine(novel.caption)}")
            append("\n\n")
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
