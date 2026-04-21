package ceui.pixiv.download.backend

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ceui.pixiv.download.model.RelativePath

/**
 * Strategy: write under a user-chosen tree URI ([treeUri]) using [DocumentFile].
 * Creates (or reuses) each intermediate directory implied by [RelativePath.directory],
 * then creates (or overwrites) the final file.
 */
class SafBackend(
    private val context: Context,
    private val treeUri: Uri,
) : StorageBackend {

    override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the filename slot is guaranteed free by
        // the time we get here. We create unconditionally so the new document
        // carries the correct mime.
        val parent = ensureDirectory(relPath.directory)
        val doc = parent.createFile(mime, relPath.filename)
            ?: error("DocumentFile.createFile returned null for $relPath under $treeUri")
        val stream = context.contentResolver.openOutputStream(doc.uri, "rwt")
            ?: error("openOutputStream returned null for ${doc.uri}")
        return StorageBackend.WriteHandle(doc.uri, stream)
    }

    override fun exists(relPath: RelativePath): Boolean {
        val parent = findDirectory(relPath.directory) ?: return false
        return parent.findFile(relPath.filename)?.exists() == true
    }

    override fun delete(relPath: RelativePath): Boolean {
        val parent = findDirectory(relPath.directory) ?: return false
        val doc = parent.findFile(relPath.filename) ?: return false
        return doc.delete()
    }

    private fun ensureDirectory(segments: List<String>): DocumentFile {
        var cur = root()
        for (seg in segments) {
            val existing = cur.findFile(seg)
            cur = when {
                existing == null       -> cur.createDirectory(seg)
                    ?: error("Could not create directory '$seg' under ${cur.uri}")
                existing.isDirectory   -> existing
                else                   -> error("Path segment '$seg' exists but is not a directory")
            }
        }
        return cur
    }

    private fun findDirectory(segments: List<String>): DocumentFile? {
        var cur: DocumentFile? = root()
        for (seg in segments) {
            cur = cur?.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: error("DocumentFile.fromTreeUri failed for $treeUri — tree not accessible")
}
