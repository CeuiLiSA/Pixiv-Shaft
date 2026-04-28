package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the export pipeline: MediaStore writes, filename
 * sanitisation, and synchronous image loading for bundled formats (EPUB).
 */
internal object ExportUtils {

    fun sanitize(name: String): String =
        ceui.pixiv.download.sanitize.FsSanitizer.cleanSegment(name, preserveExtension = true).take(80)

    /**
     * Insert a fresh entry under the active Novel bucket, run [writer] with its
     * OutputStream, and return the entry's Uri. Caller is responsible for
     * closing/flushing its own zip / bitmap / whatever wrappers.
     */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): Uri? = runCatching {
        val handle = DownloadsRegistry.downloads.openRaw(
            Bucket.Novel,
            RelativePath.parse("ShaftNovels/$fileName"),
            mimeType,
        ) ?: return@runCatching null
        handle.stream.use { writer(it) }
        handle.onFinish()
        handle.uri
    }.getOrNull()

    /** Replace all `<br/>`-like HTML tags with newlines and strip remaining markup. */
    fun brToNewline(input: String?): String {
        return input
            ?.replace(Regex("<br\\s*/?>"), "\n")
            ?.replace(Regex("<[^>]*>"), "")
            ?.trim()
            ?: ""
    }

    /** Synchronously load a bitmap via Glide. Blocks on a worker thread. */
    fun loadBitmap(context: Context, url: String, maxSide: Int = 1200): Bitmap? {
        return runCatching {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(GlideUrlChild(url))
                .submit(maxSide, maxSide)
                .get(30, TimeUnit.SECONDS)
        }.getOrNull()
    }

    /** Compress a bitmap to JPEG bytes. Recycles nothing — caller manages lifecycle. */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
