package ceui.pixiv.download.maintenance

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.RecordedPageProbe
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.ui.bulk.FetchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 把已下载文件批量重命名成**当前**命名模板渲染出的名字 —— issue #567。
 *
 * 与 [ceui.pixiv.download.importer.DownloadImporter] 同款两段式，**扫描阶段绝不写库**：
 *   1. [plan]：分页扫 illust_download_table，用每行 illustGson 里的作品元数据按当前模板
 *      重算文件名；名字已一致 / 文件已丢失 / 元数据是导入 stub / 页码未知 / 目标重名的行
 *      逐类计数跳过，只留下确定可安全改名的行；
 *   2. [commit]：逐条改磁盘文件名再同步 DB 行，`Flow<FetchEvent>` 驱动
 *      [ceui.pixiv.ui.bulk.FetchProgressDialog] 显示进度；取消即停，已改完的不回滚
 *      （幂等 —— 再跑一次剩下的行自然接上）。
 *
 * 刻意只**改文件名、不搬目录**：MediaStore 改 `DISPLAY_NAME`、SAF 走 `renameDocument`、
 * `file://` 走 `renameTo`，三条路都不跨目录。搬目录等于跨存储迁移（换 RELATIVE_PATH /
 * moveDocument / 建目录树），风险和这个 issue 要的「改名」不成比例。小说不在
 * illust_download_table 里，天然不在范围内。
 */
object RenameSweeper {

    private const val PAGE_SIZE = 200
    private const val TAG = "RenameSweeper"

    /** 一条确定要执行的改名：老名字（主键）→ 新名字，filePath 是磁盘定位句柄。 */
    data class Entry(
        val oldFileName: String,
        val newFileName: String,
        val filePath: String,
    )

    data class RenamePlan(
        val totalRows: Int,
        val entries: List<Entry>,
        /** 文件名已符合当前模板，无事可做。 */
        val alreadyMatching: Int,
        /** filePath 打不开 —— 文件已被删 / SAF 授权已失效。 */
        val fileMissing: Int,
        /** 导入进来的 stub 记录（illustGson 只有 id），得先补全元数据才有材料渲染新名。 */
        val needsEnrich: Int,
        /** 多页作品但 page 列还没回填，渲染 {page} 会错页，跳过。 */
        val unknownPage: Int,
        /** 新名字与库里另一条记录或本次计划里的另一行撞了。 */
        val nameConflicts: Int,
        /** illustGson 缺失 / 解析失败 / 模板渲染失败。 */
        val broken: Int,
        /** 动图下载记录指向 app cache 里的中间 zip（内部文件），不参与改名。 */
        val ugoiraCacheZips: Int,
    )

    /**
     * 只读扫描。阻塞式 DB + 文件探测，内部已切 [Dispatchers.IO]；[onProgress] 在 IO 线程
     * 回调（已扫描行数, 待改名数），UI 侧自己 post 回主线程。
     */
    suspend fun plan(
        context: Context,
        onProgress: (scanned: Int, toRename: Int) -> Unit = { _, _ -> },
    ): RenamePlan = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getAppDatabase(appContext).downloadDao()
        val entries = mutableListOf<Entry>()
        val plannedNames = HashSet<String>()
        var alreadyMatching = 0
        var fileMissing = 0
        var needsEnrich = 0
        var unknownPage = 0
        var nameConflicts = 0
        var broken = 0
        var ugoiraCacheZips = 0
        var offset = 0
        while (true) {
            coroutineContext.ensureActive()
            val batch = dao.getAll(PAGE_SIZE, offset)
            if (batch.isEmpty()) break
            offset += batch.size
            for (row in batch) {
                val gson = row.illustGson
                if (gson.isNullOrEmpty()) {
                    broken++
                    continue
                }
                // DownloadImporter.minimalIllustGson() 的标记：只有 id，没有标题/作者/日期,
                // 渲染出来的名字全是空洞。这些行要先跑 ImportMetadataEnricher。
                if (gson.contains("\"shaft_imported\":true")) {
                    needsEnrich++
                    continue
                }
                val bean = runCatching { Shaft.sGson.fromJson(gson, Illust::class.java) }
                    .getOrNull()
                if (bean == null || bean.id <= 0) {
                    broken++
                    continue
                }
                // Manager 给动图记录的下载行 fileName 是「标题-id.zip」、filePath 指向
                // app cache（Android10DownloadFactory22 → LegacyFile.gifZipFile）。
                // unzipAndPlay 按这个固定名回找 zip 复用本地回放 —— 改名等于断掉回放，
                // 且它本来就不是用户目录里的成品文件。只有 .gif 行（导入的成品）参与改名。
                // 成品后缀现在有两种(动图保存格式 GIF / MP4),两种都参与改名。
                val ugoiraIsMp4 = row.fileName.endsWith(".mp4", ignoreCase = true)
                if (bean.isGif() && !ugoiraIsMp4 && !row.fileName.endsWith(".gif", ignoreCase = true)) {
                    ugoiraCacheZips++
                    continue
                }
                val pageIndex = when {
                    row.page >= 0 -> row.page
                    bean.page_count <= 1 -> 0
                    else -> {
                        // 多页作品页码没回填（-1/-2），{page} 渲染出来会错页 —— 错名比不改糟。
                        unknownPage++
                        continue
                    }
                }
                if (RecordedPageProbe.usableUri(appContext, row.filePath) == null) {
                    fileMissing++
                    continue
                }
                val actualName = actualDisplayName(appContext, row.filePath)
                // 重命名不会转码。普通插画按实际文件名的后缀渲染，不能回看原图 URL：
                // 用户可能下载的是 JPEG 大图而原图为 PNG。动图仍按现有 GIF/MP4 分支处理。
                val newName = runCatching {
                    if (bean.isGif()) {
                        DownloadItems.ugoiraRelativePath(bean, ugoiraIsMp4).filename
                    } else {
                        // extractExt 把 '#' 当作 URL 片段分隔符；只传文件后缀，避免标题里的
                        // '#' 或 '?' 被误判成 URL 附件，导致扩展名丢失。
                        val extension = (actualName ?: row.fileName).substringAfterLast('.', "")
                        DownloadItems.illustRelativePath(bean, pageIndex, ".$extension").filename
                    }
                }.getOrNull()
                if (newName.isNullOrEmpty()) {
                    broken++
                    continue
                }
                // fileName 是数据库主键，不一定等于 provider 当前暴露的真实名字。
                // 同名记录只有真实名字也相同才是已匹配；主键一致但实际名字不同时
                // 仍需尝试改磁盘，主键落后但实际已匹配时仅同步 DB。
                if (row.fileName == newName && (actualName == null || actualName == newName)) {
                    alreadyMatching++
                    continue
                }
                // DB 之外也可能存在同目录目标。MediaStore 按文件名和相对路径查询，
                // file:// 直接检查磁盘；其他 provider 与不可查询项维持原先的处理方式。
                if (actualName != newName && targetNameExists(appContext, row.filePath, newName) == true) {
                    nameConflicts++
                    continue
                }
                // fileName 是主键：新名字撞上库里另一条记录或本次计划里的另一行,
                // 改下去必然 constraint 冲突 / 覆盖别人的文件,整行跳过。
                if (!plannedNames.add(newName) ||
                    (newName != row.fileName && dao.getDownloadByFileName(newName) != null)) {
                    nameConflicts++
                    continue
                }
                entries += Entry(row.fileName, newName, row.filePath)
            }
            onProgress(offset, entries.size)
        }
        RenamePlan(
            totalRows = offset,
            entries = entries,
            alreadyMatching = alreadyMatching,
            fileMissing = fileMissing,
            needsEnrich = needsEnrich,
            unknownPage = unknownPage,
            nameConflicts = nameConflicts,
            broken = broken,
            ugoiraCacheZips = ugoiraCacheZips,
        )
    }

    /**
     * 执行改名。冷 flow，collect 才开跑；先改磁盘、成功后再改 DB 行 —— DB 改不动时
     * 尽力把磁盘文件名改回去，保证「记录里的 fileName」和「磁盘上的名字」不分家
     * （详情页本地复用 / 已下载判定都靠这对字段咬合）。
     */
    fun commit(context: Context, plan: RenamePlan): Flow<FetchEvent> = flow {
        val appContext = context.applicationContext
        val dao = AppDatabase.getAppDatabase(appContext).downloadDao()
        val total = plan.entries.size
        emit(FetchEvent.Started("rename-downloads", "$total files"))
        if (total == 0) {
            emit(FetchEvent.Done(0, 0L, 0))
            return@flow
        }
        val startedAt = System.currentTimeMillis()
        var done = 0
        var failed = 0
        for ((index, entry) in plan.entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val diskNameBefore = actualDisplayName(appContext, entry.filePath)
            val diskAlreadyMatching = diskNameBefore == entry.newFileName
            // 预览到确认之间文件可能变化，执行时再检测一次可见目标冲突。
            val conflict = !diskAlreadyMatching &&
                targetNameExists(appContext, entry.filePath, entry.newFileName) == true
            val newPath = when {
                diskAlreadyMatching -> entry.filePath
                conflict -> null
                else -> runCatching { renameOnDisk(appContext, entry.filePath, entry.newFileName) }
                    .onFailure { Timber.tag(TAG).w(it, "改名失败 %s", entry.oldFileName) }
                    .getOrNull()
            }
            if (newPath == null) {
                failed++
                emit(FetchEvent.Warning("skip ${entry.oldFileName} (rename failed)"))
                continue
            }
            val dbOk = runCatching {
                dao.renameDownload(entry.oldFileName, entry.newFileName, newPath)
            }.onFailure { Timber.tag(TAG).w(it, "写库失败 %s", entry.newFileName) }.isSuccess
            if (!dbOk) {
                // 文件原本已匹配时这里只是在同步 DB，不能把它改成旧主键；磁盘确实
                // 改过时尽力恢复原来的实际名称，而不是假设它与旧主键一致。
                if (!diskAlreadyMatching) {
                    runCatching { renameOnDisk(appContext, newPath, diskNameBefore ?: entry.oldFileName) }
                }
                failed++
                emit(FetchEvent.Warning("skip ${entry.oldFileName} (db update failed)"))
                continue
            }
            done++
            if ((index + 1) % 20 == 0 || index + 1 == total) {
                emit(FetchEvent.PageReceived(index + 1, 1, 0L, done))
            }
        }
        emit(FetchEvent.Done(done, System.currentTimeMillis() - startedAt, done + failed))
    }.flowOn(Dispatchers.IO)

    /** 读取 provider 当前的真实 DISPLAY_NAME，而不是盲信数据库主键 fileName。 */
    private fun actualDisplayName(context: Context, filePath: String): String? {
        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return null
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.exists() }?.name
        if (uri.scheme != "content") return null
        val column = if (uri.authority == MediaStore.AUTHORITY) {
            MediaStore.MediaColumns.DISPLAY_NAME
        } else {
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        }
        return queryString(context, uri, column)
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
    }.getOrNull()

    /** True means a distinct visible target exists; null means this provider cannot be queried safely. */
    private fun targetNameExists(context: Context, filePath: String, newName: String): Boolean? {
        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val source = File(uri.path ?: return null)
            return File(source.parentFile ?: return null, newName).exists()
        }
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relativePath = queryString(context, uri, MediaStore.MediaColumns.RELATIVE_PATH) ?: return null
        val collection = mediaStoreCollectionUri(uri) ?: return null
        val sourceId = uri.lastPathSegment?.toLongOrNull() ?: return null
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(newName, relativePath),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (idIndex < 0) return@use null
                var conflict = false
                while (cursor.moveToNext()) {
                    if (cursor.getLong(idIndex) != sourceId) {
                        conflict = true
                        break
                    }
                }
                conflict
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mediaStoreCollectionUri(uri: Uri): Uri? {
        val encodedPath = uri.encodedPath ?: return null
        val slash = encodedPath.lastIndexOf('/')
        if (slash <= 0) return null
        return uri.buildUpon().encodedPath(encodedPath.substring(0, slash)).build()
    }

    private fun renameOnDisk(context: Context, filePath: String, newName: String): String? {
        val uri = Uri.parse(filePath)
        return when {
            uri.scheme == "file" -> {
                val src = File(uri.path ?: return null)
                val target = File(src.parentFile, newName)
                when {
                    target.exists() -> null
                    src.renameTo(target) -> Uri.fromFile(target).toString()
                    else -> null
                }
            }
            uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY -> {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                if (context.contentResolver.update(uri, values, null, null) > 0) filePath else null
            }
            uri.scheme == "content" -> {
                DocumentsContract.renameDocument(context.contentResolver, uri, newName)?.toString()
            }
            else -> null
        }
    }
}
