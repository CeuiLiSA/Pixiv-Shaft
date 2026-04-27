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
        val stream = context.contentResolver.openOutputStream(target, "rwt")
            ?: error("openOutputStream returned null for $target")
        val onFinish: () -> Unit = {
            // Clear IS_PENDING — this both makes the row visible to other apps
            // and fires a content observer notification that gallery apps use
            // to refresh their grid.
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(target, update, null, null)
        }
        return StorageBackend.WriteHandle(target, stream, onFinish)
    }

    private fun openLegacy(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        val file = legacyFile(relPath)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        val onFinish: () -> Unit = {
            // Pre-Q public-storage write — file is real, just tell MediaScanner.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
        return StorageBackend.WriteHandle(
            Uri.fromFile(file),
            FileOutputStream(file) as OutputStream,
            onFinish,
        )
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
