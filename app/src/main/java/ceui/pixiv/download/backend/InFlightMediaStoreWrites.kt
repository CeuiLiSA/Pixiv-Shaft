package ceui.pixiv.download.backend

import android.content.ContentUris
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 本进程内「正在写入」(IS_PENDING=1 且 WriteHandle 尚未 finish / abort)的
 * MediaStore 行 ID 登记表。
 *
 * [ceui.pixiv.download.maintenance.MediaStoreOrphanCleaner] 冷启动打扫孤儿
 * pending 行时,原本靠「DATE_ADDED 距今 < 60s」识别在途行;但低调下载
 * ([SilentDownload])会在 insert 时就把 DATE_ADDED 往前推 20 年,时间闸对
 * 这类行完全失效,清理协程与在途下载交错时会把正在写的行删掉 —— 写入落在
 * 已 unlink 的 FD 上静默丢弃,用户看到"下载成功"但文件不存在。改用显式
 * 登记,不依赖任何时间列。
 *
 * 只在内存里:进程被杀后登记自然清空,下个冷启动里上一会话的遗留行不在表中,
 * 照常被打扫 —— 这正是想要的语义。
 */
object InFlightMediaStoreWrites {
    private val ids = ConcurrentHashMap.newKeySet<Long>()

    fun track(uri: Uri) {
        runCatching { ids.add(ContentUris.parseId(uri)) }
    }

    fun untrack(uri: Uri) {
        runCatching { ids.remove(ContentUris.parseId(uri)) }
    }

    fun contains(id: Long): Boolean = ids.contains(id)
}
