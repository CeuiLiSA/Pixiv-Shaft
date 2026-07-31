package ceui.pixiv.download.maintenance

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
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
                val bean = runCatching { Shaft.sGson.fromJson(gson, IllustsBean::class.java) }
                    .getOrNull()
                if (bean == null || bean.id <= 0) {
                    broken++
                    continue
                }
                // Manager 给动图记录的下载行 fileName 是「标题-id.zip」、filePath 指向
                // app cache（Android10DownloadFactory22 → LegacyFile.gifZipFile）。
                // unzipAndPlay 按这个固定名回找 zip 复用本地回放 —— 改名等于断掉回放，
                // 且它本来就不是用户目录里的成品文件。只有 .gif 行（导入的成品）参与改名。
                if (bean.isGif && !row.fileName.endsWith(".gif", ignoreCase = true)) {
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
                // 动图的最终产物是 GIF，走 Ugoira bucket 的模板；其余走 Illust bucket。
                val newName = runCatching {
                    if (bean.isGif) {
                        DownloadItems.ugoiraRelativePath(bean).filename
                    } else {
                        DownloadItems.illustRelativePath(bean, pageIndex).filename
                    }
                }.getOrNull()
                if (newName.isNullOrEmpty()) {
                    broken++
                    continue
                }
                if (newName == row.fileName) {
                    alreadyMatching++
                    continue
                }
                if (RecordedPageProbe.usableUri(appContext, row.filePath) == null) {
                    fileMissing++
                    continue
                }
                // fileName 是主键：新名字撞上库里另一条记录或本次计划里的另一行,
                // 改下去必然 constraint 冲突 / 覆盖别人的文件,整行跳过。
                if (!plannedNames.add(newName) || dao.getDownloadByFileName(newName) != null) {
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
            val newPath = runCatching { renameOnDisk(appContext, entry.filePath, entry.newFileName) }
                .onFailure { Timber.tag(TAG).w(it, "改名失败 %s", entry.oldFileName) }
                .getOrNull()
            if (newPath == null) {
                failed++
                emit(FetchEvent.Warning("skip ${entry.oldFileName} (rename failed)"))
                continue
            }
            val dbOk = runCatching {
                dao.renameDownload(entry.oldFileName, entry.newFileName, newPath)
            }.onFailure { Timber.tag(TAG).w(it, "写库失败 %s", entry.newFileName) }.isSuccess
            if (!dbOk) {
                // 磁盘已改、DB 没跟上 —— 尽力改回去；改不回去也只是这一行退化成
                // 「文件名对不上记录」的旧状态，(illustId, page) 索引查询仍然命中。
                runCatching { renameOnDisk(appContext, newPath, entry.oldFileName) }
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

    /**
     * 按 filePath 的 uri 形态分发改名，返回改名后的 filePath（失败返回 null）。
     * MediaStore 行的 content://media uri 按 _ID 定位，改名后不变；SAF 的 document uri
     * 编码了路径，renameDocument 会换新 uri；file:// 直接换 basename。
     */
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
