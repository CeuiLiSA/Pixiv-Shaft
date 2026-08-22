package ceui.lisa.file

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.lisa.utils.Common
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.UgoiraDownloadRecord
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

    /**
     * 把动图成品 [from] 拷进用户存储。[asVideo] 说的是**这个文件实际是什么**(mp4 / GIF),
     * 不是设置里选了什么 —— 压制失败时保存链路会降级出 GIF,那时候名字和 mime 都得跟着是 GIF。
     */
    @JvmStatic
    fun outPutUgoira(context: Context, from: File, illust: Illust, asVideo: Boolean) {
        try {
            val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust, asVideo))
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
            // 成品（GIF / mp4）才算「已完成」里的那条下载记录（issue #920）——
            // 中间 zip 不再写库，见 [ceui.lisa.core.Manager] 完成分支。
            UgoiraDownloadRecord.record(illust, handle.uri, asVideo)
            Common.showToast(string(R.string.save_gif_success))
        } catch (t: Throwable) {
            Timber.e(t, "outPutUgoira failed")
            Common.showToast(string(R.string.save_gif_failed, errMsg(t)))
        }
    }

    @JvmStatic
    fun outPutBackupFile(context: Context, from: File, fileName: String) {
        // 备份文件本身就是 JSON（Shaft-Backup.json），之前写成 application/zip 会让
        // MediaStore 按 MIME 给文件补一个 .zip 后缀，看起来像压缩包实则不是（#949）。
        writeRaw(Bucket.Backup, "ShaftBackups/$fileName", "application/json", from, R.string.save_backup_failed)
    }

    private fun writeRaw(bucket: Bucket, rawPath: String, mime: String, from: File, failedMsgId: Int) {
        writeRawPath(bucket, RelativePath.parse(rawPath), mime, from, failedMsgId)
    }

    private fun writeRawPath(bucket: Bucket, path: RelativePath, mime: String, from: File, failedMsgId: Int) {
        try {
            val handle = DownloadsRegistry.downloads.openRaw(bucket, path, mime) ?: return
            BufferedInputStream(FileInputStream(from)).use { bis ->
                BufferedOutputStream(handle.stream).use { bos ->
                    bis.copyTo(bos)
                }
            }
            handle.onFinish()
        } catch (t: Throwable) {
            Timber.e(t, "OutPut.writeRaw failed (bucket=$bucket path=${path.joinTo()})")
            Common.showToast(string(failedMsgId, errMsg(t)))
        }
    }

    private fun string(id: Int, vararg args: Any): String =
        Shaft.getContext().getString(id, *args)

    private fun errMsg(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
