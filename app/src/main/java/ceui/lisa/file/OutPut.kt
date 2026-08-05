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

    /**
     * Write a novel temp file into the user's Novel bucket at [path].
     * The path comes from the active naming preset — see
     * [ceui.pixiv.download.config.DownloadItems.novelDestinationFromBean]
     * — so callers must pass the full relative path (directory + filename).
     * Hardcoding `ShaftNovels/` here was the legacy bypass that kept the
     * Java download path on its own naming scheme regardless of user
     * settings.
     */
    @JvmStatic
    fun outPutNovel(context: Context, from: File, path: RelativePath) {
        writeRawPath(Bucket.Novel, path, "text/plain", from, R.string.save_novel_failed)
    }

    /**
     * 同 [outPutNovel]，但落的是「小说系列 · 合并下载」桶——存储位置 / 覆盖策略跟
     * 用户给合集配的那一套走，路径来自
     * [ceui.pixiv.download.config.DownloadItems.novelSeriesMergeDestinationForSeriesItem]。
     */
    @JvmStatic
    fun outPutNovelSeriesMerge(context: Context, from: File, path: RelativePath) {
        writeRawPath(Bucket.NovelSeries, path, "text/plain", from, R.string.save_novel_failed)
    }

    @JvmStatic
    fun outPutBackupFile(context: Context, from: File, fileName: String) {
        // 备份文件本身就是 JSON（Shaft-Backup.json），之前写成 application/zip 会让
        // MediaStore 按 MIME 给文件补一个 .zip 后缀，看起来像压缩包实则不是（#949）。
        writeRaw(Bucket.Backup, "ShaftBackups/$fileName", "application/json", from, R.string.save_backup_failed)
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
