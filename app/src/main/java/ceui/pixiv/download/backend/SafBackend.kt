package ceui.pixiv.download.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import ceui.pixiv.download.model.RelativePath
import java.io.File

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
        // If openOutputStream throws, delete the empty document before
        // propagating so we don't leak 0-byte SAF files (issue #857).
        val stream = try {
            context.contentResolver.openOutputStream(doc.uri, "rwt")
                ?: error("openOutputStream returned null for ${doc.uri}")
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        val onFinish: () -> Unit = {
            // Best-effort gallery visibility: if the SAF tree maps to a real
            // path on shared storage, hand it to MediaScanner so the new file
            // shows up in the gallery without waiting for the next system rescan.
            // For trees on non-primary volumes (SD card, USB) we may not be able
            // to derive a path — in that case we skip silently.
            resolveFsPath(doc.uri)?.let { fsPath ->
                MediaScannerConnection.scanFile(context, arrayOf(fsPath), arrayOf(mime), null)
            }
        }
        // On abort, delete the SAF document we just created (always
        // unconditional — createFile produced a fresh document we own).
        val onAbort: () -> Unit = {
            runCatching { doc.delete() }
        }
        return StorageBackend.WriteHandle(doc.uri, stream, onFinish, onAbort)
    }

    private fun resolveFsPath(docUri: Uri): String? = runCatching {
        // Only ExternalStorageProvider exposes "volume:relative" doc IDs we can
        // reliably translate to a filesystem path. Downloads/MediaStore/Drive
        // providers use opaque IDs — for those, defer to the next system rescan.
        if (docUri.authority != "com.android.externalstorage.documents") return@runCatching null
        val docId = DocumentsContract.getDocumentId(docUri)
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return@runCatching null
        val (volume, relative) = parts
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        // Skip exists() — on scoped storage we may not have read access to
        // arbitrary public paths even though the file was just written via SAF.
        // MediaScannerConnection silently no-ops on missing paths anyway.
        File(root, relative).absolutePath
    }.getOrNull()

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
