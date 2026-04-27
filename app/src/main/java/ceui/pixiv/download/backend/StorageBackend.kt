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

    fun open(relPath: RelativePath, mime: String): WriteHandle

    fun exists(relPath: RelativePath): Boolean

    fun delete(relPath: RelativePath): Boolean

    /**
     * Open a write handle that replaces any existing file at [relPath].
     * Default: delete + open. [MediaStoreBackend] overrides this to update
     * the existing row in place, avoiding `contentResolver.delete()` which
     * triggers media-deletion alerts on some Android skins (e.g. HarmonyOS).
     */
    fun replace(relPath: RelativePath, mime: String): WriteHandle {
        if (exists(relPath)) delete(relPath)
        return open(relPath, mime)
    }

    data class WriteHandle(
        val uri: Uri,
        val stream: OutputStream,
        val onFinish: () -> Unit = {},
    )
}
