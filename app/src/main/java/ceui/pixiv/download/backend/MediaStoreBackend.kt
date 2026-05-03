package ceui.pixiv.download.backend

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.RelativePath
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Strategy: write via [MediaStore] on Android 10+, and via the legacy public
 * external-storage [File] API on older devices.
 *
 * [RelativePath] is interpreted as `directory/.../filename` relative to the
 * collection's root (Pictures or Downloads).
 */
class MediaStoreBackend(
    private val context: Context,
    private val collection: StorageChoice.MediaStore.Collection,
) : StorageBackend {

    override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openModern(relPath, mime)
        } else {
            openLegacy(relPath, mime)
        }
    }

    override fun exists(relPath: RelativePath): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return findUri(relPath) != null
        }
        return legacyFile(relPath).exists()
    }

    override fun delete(relPath: RelativePath): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findUri(relPath) ?: return false
            return context.contentResolver.delete(uri, null, null) > 0
        }
        return legacyFile(relPath).delete()
    }

    /**
     * On Q+, update the existing MediaStore row in place instead of
     * delete + insert. This avoids `contentResolver.delete()` which
     * triggers media-deletion alerts on HarmonyOS and similar skins.
     */
    override fun replace(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val existing = findUri(relPath)
            if (existing != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                context.contentResolver.update(existing, values, null, null)
                // If openOutputStream throws, restore IS_PENDING=0 on the
                // existing row before propagating — otherwise the pre-existing
                // file gets stuck as a `.pending-` orphan even though we never
                // wrote a byte (issue #857 manifested via "replace" path).
                val stream = try {
                    context.contentResolver.openOutputStream(existing, "rwt")
                        ?: error("openOutputStream returned null for $existing")
                } catch (e: Exception) {
                    runCatching {
                        context.contentResolver.update(
                            existing,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null, null,
                        )
                    }
                    throw e
                }
                val onFinish: () -> Unit = {
                    val update = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(existing, update, null, null)
                }
                // On abort during replace, restore IS_PENDING=0 — the row
                // pre-existed before we touched it, so deleting it would
                // unilaterally erase a file the user already had.
                val onAbort: () -> Unit = {
                    runCatching {
                        context.contentResolver.update(
                            existing,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null, null,
                        )
                    }
                }
                return StorageBackend.WriteHandle(existing, stream, onFinish, onAbort)
            }
        }
        return super.replace(relPath, mime)
    }

    private fun openModern(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the path is guaranteed free by the time
        // we get here. Always insert fresh so the row carries the correct mime.
        val collectionUri = collectionUri()
        val relativeDir = (listOf(collectionRoot()) + relPath.directory).joinToString("/") + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, relPath.filename)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // Hide the row from gallery apps until the bytes are flushed —
            // otherwise gallery apps may cache a 0-byte thumbnail and never
            // refresh, which is what users see as "doesn't appear in gallery".
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target: Uri = context.contentResolver.insert(collectionUri, values)
            ?: error("MediaStore insert failed for $relPath")
        // If openOutputStream throws after the row was inserted, the row
        // would otherwise be left stranded as a `.pending-NNNN` 0-byte file.
        // Delete it before propagating so we don't leak orphans (issue #857).
        val stream = try {
            context.contentResolver.openOutputStream(target, "rwt")
                ?: error("openOutputStream returned null for $target")
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(target, null, null) }
            throw e
        }
        val onFinish: () -> Unit = {
            // Clear IS_PENDING — this both makes the row visible to other apps
            // and fires a content observer notification that gallery apps use
            // to refresh their grid.
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(target, update, null, null)
        }
        // On abort, delete the row we just inserted. The bytes are partial /
        // zero, the row is invisible to galleries (still IS_PENDING=1), and
        // the user's file manager shows it as `.pending-NNNN`. Clean exit.
        val onAbort: () -> Unit = {
            runCatching { context.contentResolver.delete(target, null, null) }
        }
        return StorageBackend.WriteHandle(target, stream, onFinish, onAbort)
    }

    private fun openLegacy(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        val file = legacyFile(relPath)
        file.parentFile?.mkdirs()
        val newlyCreated = !file.exists() && file.createNewFile()
        // FileOutputStream 失败极罕见（disk full / 同时撤权限），但一旦失败
        // 调用方拿不到 WriteHandle、抓不到 onAbort，刚 createNewFile 的 0 字节
        // 文件就泄漏。和 openModern / SafBackend 保持一致：失败前先把刚创建的
        // 文件删掉再抛。
        val stream: OutputStream = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            if (newlyCreated) runCatching { file.delete() }
            throw e
        }
        val onFinish: () -> Unit = {
            // Pre-Q public-storage write — file is real, just tell MediaScanner.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
        // On abort, only delete if we created the file ourselves — never
        // delete a pre-existing file the user already had on disk.
        val onAbort: () -> Unit = {
            if (newlyCreated) runCatching { file.delete() }
        }
        return StorageBackend.WriteHandle(Uri.fromFile(file), stream, onFinish, onAbort)
    }

    private fun findUri(relPath: RelativePath): Uri? {
        val relativeDir = (listOf(collectionRoot()) + relPath.directory).joinToString("/") + "/"
        return queryUri(collectionUri(), relPath.filename, relativeDir)
    }

    private fun queryUri(collectionUri: Uri, displayName: String, relativeDir: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, relativeDir)
        context.contentResolver.query(collectionUri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }
        return null
    }

    private fun collectionUri(): Uri = when (collection) {
        StorageChoice.MediaStore.Collection.Images    -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        StorageChoice.MediaStore.Collection.Downloads -> {
            @Suppress("NewApi")
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    }

    private fun collectionRoot(): String = when (collection) {
        StorageChoice.MediaStore.Collection.Images    -> Environment.DIRECTORY_PICTURES
        StorageChoice.MediaStore.Collection.Downloads -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun legacyFile(relPath: RelativePath): File {
        val root = Environment.getExternalStoragePublicDirectory(collectionRoot())
        val dir = relPath.directory.fold(root) { acc, seg -> File(acc, seg) }
        return File(dir, relPath.filename)
    }
}
