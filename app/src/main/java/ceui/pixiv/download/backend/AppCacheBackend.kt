package ceui.pixiv.download.backend

import android.content.Context
import android.net.Uri
import ceui.pixiv.download.model.RelativePath
import java.io.File
import java.io.FileOutputStream

/**
 * Strategy: plain [File] I/O rooted at [Context.getExternalCacheDir]. Used
 * exclusively for [ceui.pixiv.download.model.Bucket.TempCache] — intermediate
 * artefacts like ugoira zip archives and unpacked frames.
 */
class AppCacheBackend(private val context: Context) : StorageBackend {

    override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the path is guaranteed not to exist by the
        // time we get here (OverwritePolicy.Replace deleted; Rename shifted the
        // name; Skip short-circuits in the facade). We do not second-guess it.
        val file = toFile(relPath)
        file.parentFile?.mkdirs()
        val newlyCreated = file.createNewFile()
        // On abort, only delete what we created — never touch a pre-existing
        // file. Cache lives under externalCacheDir so leakage isn't visible
        // to the user, but we still keep the surface area tight.
        val onAbort: () -> Unit = {
            if (newlyCreated) runCatching { file.delete() }
        }
        return StorageBackend.WriteHandle(Uri.fromFile(file), FileOutputStream(file), onAbort = onAbort)
    }

    override fun exists(relPath: RelativePath): Boolean = toFile(relPath).exists()

    override fun delete(relPath: RelativePath): Boolean = toFile(relPath).delete()

    private fun toFile(relPath: RelativePath): File {
        val root = context.externalCacheDir
            ?: error("externalCacheDir is null — external storage not available")
        val dir = relPath.directory.fold(root) { acc, seg -> File(acc, seg) }
        return File(dir, relPath.filename)
    }
}
