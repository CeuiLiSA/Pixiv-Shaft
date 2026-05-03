package ceui.lisa.helper

import android.content.Context
import android.net.Uri
import ceui.lisa.core.DownloadItem
import ceui.lisa.download.DownloadFileFactory
import ceui.lisa.file.LegacyFile
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems

/**
 * Legacy factory kept for binary compatibility with {@code Manager.downloadOne}.
 * Internally just wraps the new download facade — path resolution, sanitization,
 * overwrite policy and MediaStore / legacy File dispatch all live inside
 * {@link ceui.pixiv.download.Downloads}.
 *
 * Invariants:
 *   - [query] never creates a new MediaStore row; it only reports whether the
 *     resolved final path already exists. Returning non-null tells the manager
 *     to resume; null tells it to proceed with insert.
 *   - [insert] is the single allocation point — any skip/replace/rename
 *     decision was baked into [plan] at construction time by the facade.
 *
 * GIF (ugoira) downloads bypass the facade and write the zip directly to
 * [LegacyFile.gifZipFile] so that [PixivOperate.unzipAndPlay] finds it at
 * the expected path.
 */
class Android10DownloadFactory22(
    private val context: Context,
    private val item: DownloadItem,
) : DownloadFileFactory {

    private val isGif = item.illust.isGif

    private val plan = if (isGif) {
        null
    } else {
        val newItem = DownloadItems.illustPage(item.illust, item.index)
        DownloadsRegistry.downloads.plan(newItem)
    }

    private var _uri: Uri? = null
    private var onFinish: () -> Unit = {}
    private var onAbort: () -> Unit = {}
    /** 终态后再回调一次 finish/abandon 都该是 no-op，避免误清空已成功的行。 */
    private var settled: Boolean = false

    override fun query(): Uri? = _uri

    override fun insert(): Uri {
        _uri?.let { return it }
        if (isGif) {
            val zipFile = LegacyFile.gifZipFile(context, item.illust)
            _uri = Uri.fromFile(zipFile)
            return _uri!!
        }
        val p = plan!!
        check(!p.skip) {
            "Facade plan is marked skip for ${p.path} — Manager must not call insert()"
        }
        val handle = p.open()
        // Manager reopens via contentResolver.openOutputStream later — close our
        // own stream so we do not hold the FD open while the actual write happens.
        try { handle.stream.close() } catch (_: Exception) {}
        _uri = handle.uri
        onFinish = handle.onFinish
        onAbort = handle.onAbort
        return handle.uri
    }

    override fun getFileUri(): Uri = _uri ?: insert()

    override fun finishWrite() {
        // GIF (ugoira zip) 写到 app cache，相册不关心，跳过即可。
        if (isGif) return
        if (settled) return
        settled = true
        try {
            onFinish()
        } catch (e: Exception) {
            // 仅记录，不中断下载完成流程
            android.util.Log.w("Android10DownloadFactory22", "finishWrite failed: ${e.message}")
        }
    }

    override fun abandonWrite() {
        if (isGif) return
        // 已经标记 finish/abandon 过了 → 幂等保护
        if (settled) return
        settled = true
        // _uri == null 说明 insert 还没成功（factory init 后立刻失败），无可清理。
        if (_uri == null) return
        try {
            onAbort()
        } catch (e: Exception) {
            // 清理是 best-effort —— 失败也别遮蔽真正的下载错误。
            android.util.Log.w("Android10DownloadFactory22", "abandonWrite failed: ${e.message}")
        }
    }

    /** Exposed so callers who want to short-circuit can check skip state. */
    fun isSkip(): Boolean = plan?.skip ?: false
}
