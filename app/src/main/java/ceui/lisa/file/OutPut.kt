package ceui.lisa.file

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import timber.log.Timber
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
        try {
            val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust))
            if (handle == null) {
                Common.showToast(string(R.string.save_gif_exists))
                return
            }
            BufferedInputStream(FileInputStream(from)).use { bis ->
                BufferedOutputStream(handle.stream).use { bos ->
                    bis.copyTo(bos)
                }
            }
            handle.onFinish()
            Common.showToast(string(R.string.save_gif_success))
        } catch (t: Throwable) {
            Timber.e(t, "outPutGif failed")
            Common.showToast(string(R.string.save_gif_failed, errMsg(t)))
        }
    }

    @JvmStatic
    fun outPutNovel(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Novel, "ShaftNovels/$fileName", "text/plain", from, R.string.save_novel_failed)
    }

    @JvmStatic
    fun outPutBackupFile(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Backup, "ShaftBackups/$fileName", "application/zip", from, R.string.save_backup_failed)
    }

    @JvmStatic
    fun outPutFile(context: Context, from: File, fileName: String) {
        writeRaw(Bucket.Log, "ShaftFiles/$fileName", "text/plain", from, R.string.save_file_failed)
    }

    @JvmStatic
    fun outPutToDownload(context: Context, from: File, path: String, fileName: String) {
        writeRaw(Bucket.Log, "$path/$fileName", "text/plain", from, R.string.save_file_failed)
    }

    private fun writeRaw(bucket: Bucket, rawPath: String, mime: String, from: File, failedMsgId: Int) {
        try {
            val path = RelativePath.parse(rawPath)
            val handle = DownloadsRegistry.downloads.openRaw(bucket, path, mime) ?: return
            BufferedInputStream(FileInputStream(from)).use { bis ->
                BufferedOutputStream(handle.stream).use { bos ->
                    bis.copyTo(bos)
                }
            }
            handle.onFinish()
        } catch (t: Throwable) {
            Timber.e(t, "OutPut.writeRaw failed (bucket=$bucket path=$rawPath)")
            Common.showToast(string(failedMsgId, errMsg(t)))
        }
    }

    private fun string(id: Int, vararg args: Any): String =
        Shaft.getContext().getString(id, *args)

    private fun errMsg(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
