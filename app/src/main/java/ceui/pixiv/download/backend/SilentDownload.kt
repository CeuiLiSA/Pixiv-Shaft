package ceui.pixiv.download.backend

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import ceui.lisa.activities.Shaft
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 低调下载(issue #731)。
 *
 * 微信 / QQ 的选图器和系统相册的「最近」都按 MediaStore 的时间列
 * (DATE_ADDED / DATE_MODIFIED / DATE_TAKEN)或文件 mtime 倒序排列。
 * 开关打开时,下载完成后把这些时间统一往前平移 [YEARS_BACK] 年——
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

    /** 往前平移的年数。20 是 4 的倍数,闰年分布基本对齐,月日几乎原样落到旧年份上。 */
    const val YEARS_BACK = 20L

    /**
     * 回拨后的时间戳:只把年份往前推 [YEARS_BACK] 年,月/日/时/分/秒原样保留。
     *
     * 早期版本把所有文件钉死在同一个固定时间点(2007-09-10),结果是整个相册里
     * 的低调下载文件时间一模一样,用户没法再按时间分类整理。按年份平移既保留了
     * 真实的先后顺序和月日属性,又一样离「最近」足够远。
     *
     * 闰日(2 月 29 日)平移到非闰年时 [java.time.ZonedDateTime.minusYears] 会
     * 按 java.time 的规则退到 2 月 28 日,不会抛异常。
     */
    fun backdateMillis(): Long = Instant.ofEpochMilli(System.currentTimeMillis())
        .atZone(ZoneId.systemDefault())
        .minusYears(YEARS_BACK)
        .toInstant()
        .toEpochMilli()

    fun backdateSeconds(): Long = backdateMillis() / 1000

    fun enabled(): Boolean {
        val settings = Shaft.sSettings ?: return false
        return settings.isSilentDownload
    }

    /** 开关打开且产物是图片(插画 jpg/png、动图 gif)才回拨。 */
    fun applies(mime: String?): Boolean = enabled() && mime?.startsWith("image/") == true

    /**
     * 直接回拨物理文件 mtime。pre-Q 在 scanFile 之前调用(scanner 按 mtime 记
     * 时间);Q+ 在发布前 / 后调用,防止扫描用真实 mtime 把时间列刷回来。
     *
     * [stampMs] 一律由「现在」算出、而不是读文件当前 mtime 再平移:同一个文件
     * 会被回拨多次(pending → 发布后 → 写完 EXIF),读旧值就会一路往前叠成 40 年。
     */
    fun backdateFile(file: File?, stampMs: Long = backdateMillis()) {
        if (file == null || !enabled()) return
        runCatching { file.setLastModified(stampMs) }
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
     *
     * 时间戳只取一次:mtime 和三个时间列必须落在同一秒上,不然相册里同一张图的
     * 「拍摄时间」和「修改时间」会差出几秒。调用方已经用同一个戳回拨过文件时
     * (pre-Q 先回拨 mtime、扫描回调里再补时间列),把那个戳透传进来。
     */
    fun backdateRow(
        resolver: ContentResolver,
        uri: Uri,
        physicalFile: File?,
        stampMs: Long = backdateMillis(),
    ) {
        if (!enabled()) return
        backdateFile(physicalFile, stampMs)
        updateColumn(resolver, uri, MediaStore.MediaColumns.DATE_ADDED, stampMs / 1000)
        updateColumn(resolver, uri, MediaStore.MediaColumns.DATE_MODIFIED, stampMs / 1000)
        updateColumn(resolver, uri, MediaStore.Images.ImageColumns.DATE_TAKEN, stampMs)
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
