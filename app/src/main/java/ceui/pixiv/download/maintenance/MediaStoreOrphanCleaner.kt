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
 * 清扫上一次会话遗留的 `IS_PENDING=1` MediaStore 行（issue #857 的"打扫"环节）。
 *
 * 用户报告：4.63 / 4.64 网络抖动或断链时下载失效，相册根目录下出现大量
 * 0 字节 `.pending-NNNN` 临时文件，恢复网络后这些文件不会消失。
 *
 * 修复分两条线：
 *   1. **不再产生**：[ceui.lisa.core.Manager] 的 onError handler 调用
 *      `factory.abandonWrite()`，MediaStoreBackend / SafBackend 删掉刚创建
 *      的行 / 文件。新失败不再泄漏。
 *   2. **打扫历史**：本工具在 app 冷启动跑一遍，把上一次会话遗留的
 *      `IS_PENDING=1` 行删掉。
 *
 * **安全性两道闸**：
 *   - MediaStore 上 `IS_PENDING=1` 的行默认只对所属 app 可见，查到的全是我们
 *     自己的行，不会误删别 app 的待处理文件。
 *   - 用 [STALENESS_THRESHOLD_MS] 过滤 DATE_ADDED：只删 60 秒前以上的行，
 *     避免和"用户刚点单图下载"在 [ceui.lisa.core.Manager.downloadOne] 里刚
 *     插入的新 row 撞车（race window 真实存在 —— init 在 IO 协程，
 *     downloadOne 也在 IO，两条 binder 调用可能交错）。
 *
 * **行数封顶** [MAX_DELETE_PER_RUN]：极端用户可能积累上千条孤儿行，每条
 * `contentResolver.delete()` 都是一次 binder 往返，不限量会把首次启动拖慢
 * 几十秒。剩余的等下次启动继续清。
 */
object MediaStoreOrphanCleaner {

    private const val TAG = "MediaStoreOrphanCleaner"
    /** 单次启动最多删多少行；剩余下次启动再删，不要把首次启动拖死。 */
    private const val MAX_DELETE_PER_RUN = 2000
    /** 比这个新的 IS_PENDING 行不动 —— 可能是用户当前 session 里刚发起的下载。 */
    private const val STALENESS_THRESHOLD_MS = 60_000L

    /** 返回总共删掉的行数。失败时返回累计数（部分成功也算），不抛。 */
    fun cleanupPendingOrphans(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var total = 0
        // DATE_ADDED 是 Unix 秒，不是毫秒
        val cutoffSec = (System.currentTimeMillis() - STALENESS_THRESHOLD_MS) / 1000
        for (collection in collections()) {
            if (total >= MAX_DELETE_PER_RUN) break
            total += try {
                cleanupCollection(context, collection, cutoffSec, MAX_DELETE_PER_RUN - total)
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

    private fun cleanupCollection(
        context: Context,
        collection: Uri,
        cutoffSec: Long,
        budget: Int,
    ): Int {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        // 只动 60 秒以前的行 —— 避免误删用户刚发起的单图下载（DATE_ADDED 用秒）
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} < $cutoffSec"
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
            while (c.moveToNext() && deleted < budget) {
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
