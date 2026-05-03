package ceui.pixiv.download.maintenance

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import timber.log.Timber

/**
 * 清扫上一次会话遗留的 `IS_PENDING=1` MediaStore 行（issue #857 修复的"打扫"环节）。
 *
 * 用户报告：4.63 / 4.64 网络抖动或断链时下载失效，相册根目录下出现大量
 * 0 字节 `.pending-NNNN` 临时文件，恢复网络后这些文件不会消失。
 *
 * 修复分两条线：
 *   1. **不再产生**：[ceui.lisa.core.Manager] 的 onError handler 现在调用
 *      `factory.abandonWrite()`，让 MediaStoreBackend / SafBackend 删掉刚创建
 *      的行/文件。新失败不再泄漏。
 *   2. **打扫历史**：本工具，在 app 冷启动消费者动起来之前跑一遍，把上一次
 *      会话留下的 `IS_PENDING=1` 行全部删掉。
 *
 * **安全性**：MediaStore 上 `IS_PENDING=1` 的行默认只对所属 app 可见，所以
 * 我们的查询不会误触别 app 的待处理行。冷启动时机选在
 * [ceui.pixiv.ui.bulk.QueueDownloadManager.init] 协程一开始（IO 线程，
 * `resurrectInProgress` 之后、第一个 `tickle` 处理之前），保证没有正在进行
 * 的下载，所以删除是无歧义的。
 */
object MediaStoreOrphanCleaner {

    private const val TAG = "MediaStoreOrphanCleaner"

    /** 返回总共删掉的行数。失败时返回累计数（部分成功也算），不抛。 */
    fun cleanupPendingOrphans(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var total = 0
        for (collection in collections()) {
            total += try {
                cleanupCollection(context, collection)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "cleanupCollection failed for $collection")
                0
            }
        }
        if (total > 0) Timber.tag(TAG).i("cleaned $total orphan IS_PENDING rows")
        return total
    }

    private fun collections(): List<Uri> = buildList {
        add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("NewApi")
            add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
    }

    private fun cleanupCollection(context: Context, collection: Uri): Int {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1"
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // R+ 推荐用 queryArgs Bundle，setIncludePending 在 R 起 deprecated。
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            }
            resolver.query(collection, projection, args, null)
        } else {
            // Q 上 setIncludePending 是唯一公开 API；R+ 也兼容（deprecated 但 work）。
            @Suppress("DEPRECATION")
            val pendingUri = MediaStore.setIncludePending(collection)
            resolver.query(pendingUri, projection, selection, null, null)
        } ?: return 0

        var deleted = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val rowUri = ContentUris.withAppendedId(collection, id)
                runCatching { resolver.delete(rowUri, null, null) }
                    .onSuccess { if (it > 0) deleted++ }
                    .onFailure { Timber.tag(TAG).w(it, "delete $rowUri failed") }
            }
        }
        return deleted
    }
}
