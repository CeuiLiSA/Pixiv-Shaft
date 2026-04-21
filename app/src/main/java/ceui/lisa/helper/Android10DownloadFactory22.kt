package ceui.lisa.helper

import android.content.Context
import android.net.Uri
import ceui.lisa.core.DownloadItem
import ceui.lisa.download.DownloadFileFactory
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
 */
class Android10DownloadFactory22(
    private val context: Context,
    private val item: DownloadItem,
) : DownloadFileFactory {

    private val newItem = if (item.illust.isGif) {
        DownloadItems.ugoiraZip(item.illust)
    } else {
        DownloadItems.illustPage(item.illust, item.index)
    }

    private val plan = DownloadsRegistry.downloads.plan(newItem)
    private var _uri: Uri? = null

    override fun query(): Uri? = _uri

    override fun insert(): Uri {
        _uri?.let { return it }
        check(!plan.skip) {
            "Facade plan is marked skip for ${plan.path} — Manager must not call insert()"
        }
        val handle = plan.open()
        // Manager reopens via contentResolver.openOutputStream later — close our
        // own stream so we do not hold the FD open while the actual write happens.
        try { handle.stream.close() } catch (_: Exception) {}
        _uri = handle.uri
        return handle.uri
    }

    override fun getFileUri(): Uri = _uri ?: insert()

    /** Exposed so callers who want to short-circuit can check skip state. */
    fun isSkip(): Boolean = plan.skip
}
