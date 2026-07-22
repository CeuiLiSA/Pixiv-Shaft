package ceui.pixiv.download

import android.content.Context
import android.net.Uri
import ceui.lisa.database.AppDatabase
import timber.log.Timber
import java.io.FileNotFoundException

/**
 * 「已存在则跳过」的第二道判断：文件系统说不存在时，再问一次下载记录。
 *
 * 为什么需要：[Downloads.applyOverwritePolicy] 的 Skip 分支只问
 * `backend.exists(渲染出来的路径)`，而这个路径是拿**当前模板**算出来的。用户升级前
 * 用的是别的命名规则、或记录是 `DownloadImporter` 从旧版命名的文件扫进来的
 * （issue #953），那条路径上当然什么都没有 —— 于是明明盘上躺着同一张图，还是会
 * 重新下一份，MediaStore 再给它加个 ` (1)` 后缀。这正是 #953 报告的现象。
 *
 * 为什么不做进 [Downloads]：`Downloads.plan()` 会被 `DownloadItem` 的 Java 构造函数
 * 经 `FileCreator.customFileName` 在**主线程**调用（见 `SafeTemplateRender` 的注释），
 * 往里面塞 DB 查询就是 ANR。所以判断留在 `Manager.downloadOne` 的 IO 线程上，
 * facade 保持零 DB 依赖，它那套纯 JVM 单测也不用动。
 */
object RecordedPageProbe {

    /**
     * 这一页有没有一条**且文件确实还在**的下载记录。
     *
     * 必须验文件还在。只凭记录就跳过的话，用户在文件管理器里删掉图之后就再也下不回来了
     * —— [ceui.pixiv.download.backend.MediaStoreBackend.exists] 顶上那整段注释讲的就是
     * 这个坑（孤儿行导致 Skip 静默吞掉下载 / Rename 一路 `(1)` `(2)`），别再踩一遍。
     *
     * 判不准时一律返回 false（照常下载）。多下一份最多浪费点流量，错误跳过则是直接
     * 丢掉用户要的文件。
     *
     * 阻塞式 DB + IO，必须在工作线程调。
     */
    @JvmStatic
    fun hasUsableRecord(context: Context, illustId: Long, page: Int): Boolean {
        if (illustId <= 0L || page < 0) return false
        return try {
            val dao = AppDatabase.getAppDatabase(context.applicationContext).downloadDao()
            val row = dao.getDownloadedPage(illustId, page) ?: return false
            val path = row.filePath
            if (path.isNullOrEmpty()) return false
            val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
            fileStillThere(context, uri)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "记录探测失败 illust=%d page=%d，按未下载处理", illustId, page)
            false
        }
    }

    /**
     * 记录里的 uri 还打得开吗。content://（MediaStore / SAF）和 file:// 都走
     * openFileDescriptor —— 比 `File.exists()` 可靠：Q+ 作用域存储下对公共目录的
     * `File.exists()` 本来就读不到。
     *
     * 只有确确实实打开成功才算"文件还在"。FileNotFoundException 是明确的"没了"；
     * SecurityException（权限被撤 / SAF 树没授权）等其它异常一律当"不确定"，
     * 同样返回 false 走重新下载。
     */
    private fun fileStillThere(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: FileNotFoundException) {
        false
    } catch (t: Throwable) {
        Timber.tag(TAG).d(t, "打不开 %s，按文件已丢失处理", uri)
        false
    }

    private const val TAG = "RecordedPageProbe"
}
