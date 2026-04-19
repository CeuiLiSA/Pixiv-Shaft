package ceui.pixiv.ui.novel.reader.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the export pipeline: MediaStore writes, filename
 * sanitisation, and synchronous image loading for bundled formats (EPUB).
 */
internal object ExportUtils {

    private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/ShaftNovels"

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim()
        return cleaned.ifEmpty { "novel" }.take(80)
    }

    /**
     * Insert a fresh MediaStore entry in Downloads/ShaftNovels, run [writer]
     * with its OutputStream, and return the entry's Uri. Caller is responsible
     * for closing/flushing its own zip / bitmap / whatever wrappers.
     */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { writer(it) } ?: return null
            uri
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
        }.getOrNull()
    }

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
