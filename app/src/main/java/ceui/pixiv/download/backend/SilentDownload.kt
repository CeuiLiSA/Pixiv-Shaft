package ceui.pixiv.download.backend

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import ceui.lisa.activities.Shaft
import java.io.File

/**
 * 低调下载(issue #731)。
 *
 * 微信 / QQ 的选图器和系统相册的「最近」都按 MediaStore 的时间列
 * (DATE_ADDED / DATE_MODIFIED / DATE_TAKEN)或文件 mtime 倒序排列。
 * 开关打开时,下载完成后把这些时间统一回拨到一个固定的早期时间点——
 * 文件照常保存、照常能在目录里找到,只是不会插队到任何「最近」列表前排。
 *
 * 不采用 .nomedia / 隐藏目录方案:issue 里已验证那会让下载本身失败。
 * 所有回拨都是 best-effort,失败绝不影响下载结果。
 *
 * 只作用于图片([applies] 按 mime 过滤):设置文案只承诺图片,小说 txt /
 * 设置备份等走同一 backend 的产物不回拨,否则用户在 Downloads 里按时间
 * 反而找不到刚导出的文件。
 */
object SilentDownload {

    // 2007-09-10, pixiv 上线日。低调下载的文件都归档到这一天,远离任何「最近」。
    const val BACKDATE_MS = 1_189_382_400_000L
    const val BACKDATE_SEC = BACKDATE_MS / 1000

    fun enabled(): Boolean {
        val settings = Shaft.sSettings ?: return false
        return settings.isSilentDownload
    }

    /** 开关打开且产物是图片(插画 jpg/png、动图 gif)才回拨。 */
    fun applies(mime: String?): Boolean = enabled() && mime?.startsWith("image/") == true

    /**
     * 直接回拨物理文件 mtime。pre-Q 在 scanFile 之前调用(scanner 按 mtime 记
     * 时间);Q+ 在发布前 / 后调用,防止扫描用真实 mtime 把时间列刷回来。
     */
    fun backdateFile(file: File?) {
        if (file == null || !enabled()) return
        runCatching { file.setLastModified(BACKDATE_MS) }
    }

    /**
     * Q+ 专用,在清 IS_PENDING **之前**调用:发布触发的扫描会把 date_modified
     * 重算成物理文件 mtime,而行级 update 该列在部分版本会被 MediaProvider 静默
     * 忽略 —— 唯一可靠的写入点是趁行还 pending 时把 `.pending-` 文件的 mtime
     * 先回拨(发布时的重命名保留 mtime,扫描随即记下旧时间)。
     */
    fun backdatePendingFile(resolver: ContentResolver, uri: Uri) {
        if (!enabled()) return
        backdateFile(resolveDataFile(resolver, uri))
    }

    /**
     * 回拨 MediaStore 行的时间列。在行对其它 App 可见(IS_PENDING=0 / scanFile
     * 完成)之后调用。先改物理文件 mtime,再更新时间列;三列各自单独一次
     * update —— 某列在某 ROM 上被拒绝(抛错)时不连累其它列。
     */
    fun backdateRow(resolver: ContentResolver, uri: Uri, physicalFile: File?) {
        if (!enabled()) return
        backdateFile(physicalFile)
        updateColumn(resolver, uri, MediaStore.MediaColumns.DATE_ADDED, BACKDATE_SEC)
        updateColumn(resolver, uri, MediaStore.MediaColumns.DATE_MODIFIED, BACKDATE_SEC)
        updateColumn(resolver, uri, MediaStore.Images.ImageColumns.DATE_TAKEN, BACKDATE_MS)
    }

    /**
     * 只知道 uri 的调用点用这个(如 [ceui.pixiv.download.ExifKeywordWriter]:它在
     * finishWrite 之后整文件重写 JPEG,会把刚回拨的 mtime / date_modified 刷回
     * 「现在」,写完必须再回拨一次)。file:// 直接改 mtime;SAF 的 document uri
     * 反解出物理路径改 mtime(静默模式下行本来就没进 MediaStore,没有列可改);
     * 其余 content:// 先经 DATA 列反查物理文件再走 [backdateRow]。
     */
    fun backdateUri(resolver: ContentResolver, uri: Uri?) {
        if (uri == null || !enabled()) return
        when {
            uri.scheme == "file" -> uri.path?.let { backdateFile(File(it)) }
            uri.authority == "com.android.externalstorage.documents" ->
                backdateFile(resolveExternalDocPath(uri))
            else -> backdateRow(resolver, uri, resolveDataFile(resolver, uri))
        }
    }

    private fun updateColumn(resolver: ContentResolver, uri: Uri, column: String, value: Long) {
        runCatching {
            resolver.update(uri, ContentValues().apply { put(column, value) }, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveDataFile(resolver: ContentResolver, uri: Uri): File? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let(::File) else null
        }
    }.getOrNull()

    /**
     * ExternalStorageProvider 的 doc ID 是 "volume:relative",可以翻译成物理路径
     * (与 [SafBackend.resolveFsPath] 同一套规则);其它 provider 的 ID 不透明,放弃。
     */
    private fun resolveExternalDocPath(docUri: Uri): File? = runCatching {
        val docId = DocumentsContract.getDocumentId(docUri)
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return@runCatching null
        val (volume, relative) = parts
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        File(root, relative)
    }.getOrNull()
}
