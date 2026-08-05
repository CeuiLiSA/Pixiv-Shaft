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

    /**
     * Insert a fresh entry under [bucket] (the active Novel bucket by default;
     * merged series files pass [Bucket.NovelSeries]) at [destination]
     * (full directory + filename, already rendered through the user's active
     * naming preset — see
     * [ceui.pixiv.download.config.DownloadItems.novelDestinationFromLoxia]),
     * run [writer] with its OutputStream, and return the entry's Uri. Caller
     * is responsible for closing/flushing its own zip / bitmap / whatever
     * wrappers.
     *
     * Returns `null` only when the overwrite policy skipped the write. On any
     * I/O / MediaStore failure it does NOT swallow — it propagates the (often
     * OEM-specific, user-actionable) exception so the export pipeline can show
     * the real cause instead of a generic "无法写入 Downloads".
     */
    fun saveToDownloads(
        context: Context,
        destination: RelativePath,
        mimeType: String,
        bucket: Bucket = Bucket.Novel,
        writer: (OutputStream) -> Unit,
    ): Uri? {
        val handle = DownloadsRegistry.downloads.openRaw(
            bucket,
            destination,
            mimeType,
        ) ?: return null
        // The backend throws deliberately-actionable, OEM-specific messages
        // (e.g. "下载目录被系统占用，无法写入 …/，请在文件管理器删掉同名旧文件后重试"
        // on vivo / MIUI / HarmonyOS skins) that the user must see. On a
        // mid-write failure, abort the pending row first — otherwise a 0-byte
        // `.pending-` orphan leaks and trips the same guard on the next try —
        // then rethrow so the caller surfaces the real cause.
        try {
            handle.stream.use { writer(it) }
            handle.onFinish()
        } catch (t: Throwable) {
            handle.onAbort()
            throw t
        }
        return handle.uri
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
