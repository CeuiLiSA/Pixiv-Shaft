package ceui.pixiv.download.backend

import android.net.Uri
import ceui.pixiv.download.model.RelativePath
import java.io.OutputStream

/**
 * Strategy interface that abstracts the "where bytes go" decision.
 *
 * Implementations:
 *   - [MediaStoreBackend] — Pictures/Downloads via `MediaStore` (default on Q+).
 *   - SafBackend          — user-chosen tree via `DocumentFile` (opt-in).
 *   - AppCacheBackend     — `context.externalCacheDir` (always for [ceui.pixiv.download.model.Bucket.TempCache]).
 *
 * Contract:
 *   - [open] may create any missing intermediate directories implied by [relPath].
 *   - [relPath] has already been sanitized by [ceui.pixiv.download.sanitize.FsSanitizer];
 *     backends MUST NOT perform further renaming.
 */
interface StorageBackend {

    /**
     * 目标是否落在 `content://` 语义之上（MediaStore / SAF），需要经过
     * ContentProvider 提交、存在 IS_PENDING 半成品窗口。`Manager` 用它决定要不要
     * 走 staging（先写本地 `.part`、提交时才建目标行，避免 0 字节 `.pending` 泄漏 +
     * 提供统一的断点续传落点）。[AppCacheBackend] 是纯 `file://`，覆写为 `false`。
     */
    val isContentScheme: Boolean get() = true

    fun open(relPath: RelativePath, mime: String): WriteHandle

    fun exists(relPath: RelativePath): Boolean

    fun delete(relPath: RelativePath): Boolean

    /**
     * Open a write handle that replaces any existing file at [relPath].
     * Default: delete + open. [MediaStoreBackend] overrides this to update
     * the existing row in place, avoiding `contentResolver.delete()` which
     * triggers media-deletion alerts on some Android skins (e.g. HarmonyOS).
     * It also reclaims orphan rows (file on disk but row missing or invisible)
     * via `MediaScannerConnection`, so a re-download lands in the user's
     * configured directory instead of being scattered into sibling
     * directories by HarmonyOS / MIUI MediaStore auto-rename behaviour.
     */
    fun replace(relPath: RelativePath, mime: String): WriteHandle {
        if (exists(relPath)) delete(relPath)
        return open(relPath, mime)
    }

    /**
     * [onFinish] is called once after bytes are committed (download success).
     * [onAbort] is called when the download was abandoned mid-write
     * (network failure, user cancellation, OOM, …) — backends MUST clean up
     * any artefacts they materialised during [open] / [replace], otherwise
     * a 0-byte / partial pending file will leak into the user's gallery.
     * Exactly one of [onFinish] / [onAbort] is invoked per handle. After
     * [onAbort] the handle is dead — do not call [onFinish] on it.
     */
    data class WriteHandle(
        val uri: Uri,
        val stream: OutputStream,
        val onFinish: () -> Unit = {},
        val onAbort: () -> Unit = {},
    )
}
