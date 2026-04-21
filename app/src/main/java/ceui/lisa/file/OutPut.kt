package ceui.lisa.file

import android.content.Context
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Legacy "copy local file into user storage" helpers. Rewritten to route every
 * write through the unified download facade.
 */
object OutPut {

    @JvmStatic
    fun outPutGif(context: Context, from: File, illust: IllustsBean) {
        val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust))
        if (handle == null) {
            Common.showToast("GIF已存在")
            return
        }
        try {
            BufferedInputStream(FileInputStream(from)).use { bis ->
                BufferedOutputStream(handle.stream).use { bos ->
                    bis.copyTo(bos)
                }
            }
            Common.showToast("GIF保存成功")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun outPutNovel(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Novel, RelativePath.parse("ShaftNovels/$fileName"), "text/plain", from)
    }

    @JvmStatic
    fun outPutBackupFile(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Backup, RelativePath.parse("ShaftBackups/$fileName"), "application/zip", from)
    }

    @JvmStatic
    fun outPutFile(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Log, RelativePath.parse("ShaftFiles/$fileName"), "text/plain", from)
    }

    @JvmStatic
    fun outPutToDownload(context: Context, from: File, path: String, fileName: String) {
        writeRaw(Bucket.Log, RelativePath.parse("$path/$fileName"), "text/plain", from)
    }

    private fun writeRaw(bucket: Bucket, path: RelativePath, mime: String, from: File) {
        val handle = DownloadsRegistry.downloads.openRaw(bucket, path, mime) ?: return
        try {
            BufferedInputStream(FileInputStream(from)).use { bis ->
                BufferedOutputStream(handle.stream).use { bos ->
                    bis.copyTo(bos)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
