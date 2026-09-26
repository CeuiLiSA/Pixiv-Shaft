package ceui.pixiv.download

import android.content.Context
import androidx.annotation.WorkerThread
import android.net.Uri
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.FileCreator
import ceui.pixiv.api.model.Illust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「这一页本地有没有」的**唯一规则**。
 *
 * 在这之前同一套语义被抄了两份 —— IllustAdapter.scanLocalDownloads（批量，自己写了两段式 +
 * 增量缓存）与 FragmentImageDetail.findDownloadedPageUri（单页，同样两段式）；本次尝试产生的
 * 第三份还漏抄了第 2 段兜底。判定散在渲染流程里，抄写就不会停 —— 所以规则收在这里，形状按
 * **性能需求**保留两种（批量一条 IN 查询 vs 单页一条主键查询，这是性能差异，不是重复实现）。
 *
 * 两段式，两段都必要：
 * 1. `(illustId, page)` 复合索引（v41）—— 跟文件叫什么名字无关，用户换过命名模板、或记录是
 *    DownloadImporter 从旧版命名的文件扫进来的（issue #953），照样命中；
 * 2. 落空时退回 [FileCreator.customFileName] + fileName 主键查询 —— v41 之前的存量行 `page`
 *    还是 -1（DownloadPageBackfill 没跑完 / 文件名解析不出页码），只能靠这条兜。
 *
 * 两段都复用 [RecordedPageProbe] 验「文件确实还在」，孤儿记录不参与命中。
 *
 * ⚠️ 与 `Common.isIllustDownloaded` / `FileCreator.isExist` / `Downloads.existsAt` 是**两套不同
 * 语义**，不要合并：那套按**当前命名模板**算出的路径探文件系统、不查下载记录，服务的是
 * 「下载按钮状态 / 已下载探针」，输出 boolean 而非可渲染来源，也正是 issue #953 那个坑的来源。
 * 本对象走**记录两段式**。
 */
object DownloadedPageIndex {

    /**
     * 批量：给「一次要整页表」的场景（IllustAdapter.scanLocalDownloads）用，一条 IN 查询覆盖全作品。
     * 查 `0 until pageCount` 这些页。
     *
     * 同一页有重复记录时保留第一条仍能打开的（查询按 downloadTime 倒序），不让较新的孤儿行
     * 遮住仍完好的旧文件。返回的 Map 只含确实命中且文件可读的页。
     *
     * @param skip 调用方**已经确认可读**的页（增量缓存）。这些页不再重复查、也不再重复
     *   openFileDescriptor 验一遍 —— B 展开时会再扫一次以发现「构造后新下载」的页，不排掉它们
     *   就等于每次展开都把整部作品重验一遍。
     *
     * 主线程安全（内部切 IO）。
     */
    suspend fun pages(
        context: Context,
        illust: Illust,
        pageCount: Int,
        skip: Set<Int> = emptySet(),
    ): Map<Int, Uri> = withContext(Dispatchers.IO) { scan(context, illust, pageCount, skip) }

    /**
     * 阻塞版：Java 侧（IllustAdapter 的扫描线程池）没法直接调 suspend 函数，所以开这一个口子。
     * 与 [pages] 共用同一份规则实现，**只能在已经处于工作线程时调**。
     */
    @JvmStatic
    @WorkerThread
    fun pagesBlocking(
        context: Context,
        illust: Illust,
        pageCount: Int,
        skip: Set<Int>,
    ): Map<Int, Uri> = scan(context, illust, pageCount, skip)

    private fun scan(
        context: Context,
        illust: Illust,
        pageCount: Int,
        skip: Set<Int>,
    ): Map<Int, Uri> {
        val appContext = context.applicationContext
        val found = HashMap<Int, Uri>()
        try {
            val dao = AppDatabase.getAppDatabase(appContext).downloadDao()

            // 第 1 段：复合索引。与文件叫什么名字无关。
            for (row in dao.getDownloadedPages(illust.id)) {
                val page = row.page
                if (page < 0 || page >= pageCount || page in skip || found.containsKey(page)) continue
                val path = row.filePath
                if (path.isNullOrEmpty()) continue
                RecordedPageProbe.usableUri(appContext, path)?.let { found[page] = it }
            }

            // 第 2 段：fileName 主键兜底。只补上面没查到的页。
            val fileNames = ArrayList<String>()
            val pageByFileName = HashMap<String, Int>()
            for (page in 0 until pageCount) {
                if (page in skip || found.containsKey(page)) continue
                val fileName = FileCreator.customFileName(illust, page)
                fileNames.add(fileName)
                pageByFileName[fileName] = page
            }
            if (fileNames.isNotEmpty()) {
                // 单次 IN 查询取代 N 次 Room 调用。Pixiv 多 P 上限远低于 SQLite 变量上限。
                for (row in dao.getDownloadsByFileNames(fileNames)) {
                    val page = pageByFileName[row.fileName] ?: continue
                    if (page in skip || found.containsKey(page)) continue
                    val path = row.filePath
                    if (path.isNullOrEmpty()) continue
                    RecordedPageProbe.usableUri(appContext, path)?.let { found[page] = it }
                }
            }
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "批量下载页索引失败 illust=%d", illust.id)
        }
        return found
    }

    /**
     * 单页：就是规则本身，批量版与 PageImageSourceResolver 都基于它。
     *
     * 返回 null 表示这页没下过 / 记录损坏 / 文件已被删，调用方回退网络。主线程安全（内部切 IO）。
     */
    suspend fun page(context: Context, illust: Illust, page: Int): Uri? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            try {
                RecordedPageProbe.findUsableUri(appContext, illust.id, page)
                    ?: AppDatabase.getAppDatabase(appContext)
                        .downloadDao()
                        .getDownloadByFileName(FileCreator.customFileName(illust, page))
                        ?.filePath
                        ?.let { RecordedPageProbe.usableUri(appContext, it) }
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "单页下载记录查询失败 illust=%d page=%d", illust.id, page)
                null
            }
        }

    private const val TAG = "DownloadedPageIndex"
}