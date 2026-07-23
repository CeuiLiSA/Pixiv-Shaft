package ceui.pixiv.download.importer

import android.content.Context
import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * 扫描用户授权的目录，把里面认得出作品 id 的图片补成 `illust_download_table` 里的
 * 下载记录 —— issue #953。
 *
 * 解决的问题：旧版（<=4.5.7）下载的图片，新版一律显示"未下载"。根因不是配置错了，
 * 而是新旧两版的文件名消毒规则不同（旧版把 `-` `,` 换成 `_`，新版保留），**任何模板
 * 都表达不出这个差异**；卸载重装过的用户还会再叠一层 MediaStore 行归属丢失。
 * 所以只能反过来从盘上的文件名解析 id，重建记录。
 *
 * 两段式，中间必须让用户看一眼：
 *   1. [scanAndPlan] —— 只读不写，产出 [ImportPlan] 给 UI 展示"扫到多少 / 认出多少 /
 *      要跳过多少 / 认不出的长什么样"。
 *   2. [commit] —— 用户确认后才写库，且走 IGNORE 语义，不覆盖任何已有记录。
 */
object DownloadImporter {

    /** SQLite 变量上限是 999，`IN (...)` 查询按这个分片。 */
    private const val SQL_VAR_CHUNK = 900

    /** 一次 insert 的行数。太大 binder / 事务压力大，太小往返次数多。 */
    private const val INSERT_CHUNK = 500

    /** 预览里给用户看的"认不出来"的样例文件名条数。 */
    private const val UNRECOGNIZED_SAMPLES = 5

    private const val TAG = "DownloadImporter"

    /** 待写入的一行。 */
    data class PendingRow(
        val fileName: String,
        val docUri: Uri,
        val illustId: Long,
        /** 0 基页码；-2 表示文件名有页码但基准无法安全判定。 */
        val zeroBasedPage: Int,
        val lastModified: Long,
    )

    /** [scanAndPlan] 的产出。写库前的完整预览，UI 直接照着渲染。 */
    data class ImportPlan(
        val treeUri: Uri,
        val scannedFiles: Int,
        /** 解析出 id 的文件数。 */
        val recognizedFiles: Int,
        /** 去重后的作品数。 */
        val works: Int,
        /** fileName 已经在库里、这次会跳过的文件数。 */
        val alreadyRecorded: Int,
        /** 认不出 id 的文件数。 */
        val unrecognized: Int,
        val unrecognizedSamples: List<String>,
        val rows: List<PendingRow>,
    ) {
        val newRows: Int get() = rows.size
        val newWorks: Int get() = rows.mapTo(HashSet()) { it.illustId }.size
    }

    data class ImportResult(val inserted: Int, val works: Int)

    /**
     * 扫描 + 解析 + 查重，**不写任何库**。
     *
     * 挂起函数，跑在 [Dispatchers.IO]；可取消（扫描每读完一层目录、解析每 512 个文件
     * 检查一次）。
     */
    suspend fun scanAndPlan(
        context: Context,
        treeUri: Uri,
        onProgress: (scanned: Int, recognized: Int) -> Unit = { _, _ -> },
    ): ImportPlan = withContext(Dispatchers.IO) {
        val parser = NameParser.create()
        val unrecognizedSamples = ArrayList<String>()
        // 只计数，不留 ScannedFile —— 认出来的那些已经在 byWork 里，再存一份整表
        // 就是 10 万个对象的白白占用。
        var scannedCount = 0
        var recognizedCount = 0
        var unrecognized = 0

        // 同一作品的所有页先攒在一起 —— 页码基准（p0 起还是 p1 起）只能靠整个作品的
        // 页码集合推断，单看一个文件推不出来。
        val byWork = HashMap<Long, MutableList<Pair<ScannedFile, NameMatch>>>()

        DownloadTreeScanner.scan(
            context = context,
            treeUri = treeUri,
            onProgress = { files, _ -> onProgress(files, recognizedCount) },
        ) { file ->
            scannedCount++
            val hit = parser.parse(file.displayName)
            if (hit == null) {
                unrecognized++
                if (unrecognizedSamples.size < UNRECOGNIZED_SAMPLES) {
                    unrecognizedSamples.add(file.displayName)
                }
            } else {
                recognizedCount++
                byWork.getOrPut(hit.illustId) { mutableListOf() }.add(file to hit)
            }
        }
        coroutineContext.ensureActive()
        // 同名文件（不同子目录下重名）只能留一条 —— 本表主键就是 fileName。
        // 保留最新的那个，和"用户最近一次下载覆盖了旧的"直觉一致。
        val deduped = LinkedHashMap<String, PendingRow>()
        for ((illustId, entries) in byWork) {
            // 基准按整个作品定，不用单个 hit 声明的那个 —— 理由见 PageBaseInference。
            val base = PageBaseInference.infer(entries.map { it.second })
            for ((file, hit) in entries) {
                // 没有 p0、也没有可信设置时，0 基缺首页与 1 基完整集无法区分。
                // 宁可写 -2 让按页查询落空，也不能猜错后把第 N 页显示成第 N±1 页。
                val page = PageBaseInference.toZeroBasedOrNull(hit.printedPage, base)
                    ?: DownloadPageBackfillPage.UNPARSEABLE
                val row = PendingRow(
                    fileName = file.displayName,
                    docUri = file.docUri,
                    illustId = illustId,
                    zeroBasedPage = page,
                    lastModified = file.lastModified,
                )
                val prev = deduped[row.fileName]
                if (prev == null || prev.lastModified < row.lastModified) {
                    deduped[row.fileName] = row
                }
            }
        }
        coroutineContext.ensureActive()

        val dao = AppDatabase.getAppDatabase(context.applicationContext).downloadDao()
        val existing = HashSet<String>()
        for (chunk in deduped.keys.chunked(SQL_VAR_CHUNK)) {
            coroutineContext.ensureActive()
            runCatching { existing.addAll(dao.filterExistingFileNames(chunk)) }
                .onFailure { Timber.tag(TAG).w(it, "查重失败，这批按未入库处理") }
        }

        val rows = deduped.values
            .filterNot { it.fileName in existing }
            .sortedWith(compareBy({ it.illustId }, { it.zeroBasedPage }, { it.fileName }))

        ImportPlan(
            treeUri = treeUri,
            scannedFiles = scannedCount,
            recognizedFiles = recognizedCount,
            works = byWork.size,
            alreadyRecorded = existing.size,
            unrecognized = unrecognized,
            unrecognizedSamples = unrecognizedSamples,
            rows = rows,
        )
    }

    /**
     * 写库。只在用户确认后调。
     *
     * illustGson 写的是 `{"id":123,"shaft_imported":true}` 这样的最小 JSON —— 下游全部
     * 能降级：
     *  - `hasDownloadRecord` 只查 illustId 索引；
     *  - `DoneListV3Fragment.groupByIllust` 靠 `"id":(\d+)` 分组；
     *  - 卡片缩略图本来就优先读 filePath 指的本地文件，标题拿不到会回退成文件名。
     * 真实的标题 / 作者 / 封面由 [ImportMetadataEnricher] 事后按需补。
     */
    suspend fun commit(context: Context, plan: ImportPlan): ImportResult = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getAppDatabase(context.applicationContext).downloadDao()
        var inserted = 0
        for (chunk in plan.rows.chunked(INSERT_CHUNK)) {
            coroutineContext.ensureActive()
            val entities = chunk.map { row ->
                DownloadEntity().apply {
                    fileName = row.fileName
                    filePath = row.docUri.toString()
                    illustId = row.illustId
                    page = row.zeroBasedPage
                    downloadTime = row.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis()
                    illustGson = minimalIllustGson(row.illustId)
                }
            }
            runCatching { dao.insertIgnoreAll(entities) }
                .onSuccess { inserted += chunk.size }
                .onFailure { Timber.tag(TAG).w(it, "导入写库失败，跳过这批 %d 行", chunk.size) }
        }
        val workIds = plan.rows.mapTo(HashSet()) { it.illustId }
        ImportEnrichQueue.enqueue(workIds)
        // 已完成列表是 Room reactive Flow，写完自己就刷新了，这里不用再 poke。
        Timber.tag(TAG).i("导入完成：%d 行 / %d 个作品", inserted, workIds.size)
        ImportResult(inserted = inserted, works = workIds.size)
    }

    /** 供 [ImportMetadataEnricher] 覆盖前对照，也是导入时写进去的初始值。 */
    fun minimalIllustGson(illustId: Long): String = "{\"id\":$illustId,\"shaft_imported\":true}"
}

/**
 * 判定文件名里的 `p1` 到底是第 0 页还是第 1 页。
 *
 * 单看一个文件名判不出来，但把**同一作品的所有页**放在一起看，证据就有强弱之分。
 * 按可信度排序：
 *
 *  1. **出现过 `p0`** —— 铁证，1 基方案永远不会写出 `p0`。
 *  2. **没有页码后缀** —— 单图作品，基准无所谓，都落在第 0 页。
 *  3. **命中可信配置声明的基准** —— 已持久化的当前配置，或旧版仍保留的 cell 设置。
 *  4. 都没有（通用历史模板 / 启发式）—— 返回 [PageBase.UNKNOWN]，调用方必须把该行
 *     标成不可解析，绝不能猜成 1 基。
 *
 * 为什么不直接信模板声明的基准：同一个模板串在 [NameParser] 的候选表里会以两种基准
 * 各注册一次（用户可能改过 `pageIndexFrom1`，盘上混着两种命名），谁先命中取决于候选
 * 顺序 —— 那是个随机结果。而"这个作品有没有 p0"是实打实的证据。判错的后果不是查不到，
 * 是把第 N 页的本地图错配到第 N±1 页，比查不到还糟，所以这里只信证据。
 */
internal object PageBaseInference {

    fun infer(hits: List<NameMatch>): PageBase {
        val printed = hits.mapNotNull { it.printedPage }
        if (printed.isEmpty()) return PageBase.ZERO
        if (printed.min() == 0) return PageBase.ZERO
        // 没出现 p0：可能真是 1 基，也可能是 0 基但第一页恰好不在扫描结果里。
        // 只有所有可信声明一致时才采用；没有声明或声明冲突都必须保持 UNKNOWN。
        val declared = hits.asSequence()
            .map { it.pageBase }
            .filter { it != PageBase.UNKNOWN }
            .distinct()
            .toList()
        return declared.singleOrNull() ?: PageBase.UNKNOWN
    }

    fun toZeroBasedOrNull(printedPage: Int?, base: PageBase): Int? = when {
        printedPage == null -> 0
        base == PageBase.ONE -> (printedPage - 1).coerceAtLeast(0)
        base == PageBase.ZERO -> printedPage
        else -> null
    }
}

/** 与 v41 page 列约定一致；放这里避免 importer 反向依赖 database backfill。 */
private object DownloadPageBackfillPage {
    const val UNPARSEABLE = -2
}

/**
 * 待补全元数据的作品 id 队列。放 MMKV 而不是 DB —— 想在库里找出"哪些行是最小 JSON"
 * 只能对 illustGson blob 做全表 LIKE / LENGTH 扫描，正是本项目一直在躲的那种查询
 * （见 `DownloadDao.hasDownloadRecordByIllustIdIndexed` 的注释）。导入时我们本来就
 * 精确知道是哪些 id，直接记下来即可。
 */
object ImportEnrichQueue {

    private const val KEY = "download_import_enrich_queue_v1"

    /** 上限：超过就不再往里塞。补全本身是 2s/个的限速任务，队列再长也跑不完。 */
    private const val MAX = 20_000

    @Synchronized
    fun enqueue(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val merged = LinkedHashSet(peek())
        for (id in ids) {
            if (merged.size >= MAX) break
            merged.add(id)
        }
        write(merged)
    }

    @Synchronized
    fun peek(): List<Long> = runCatching {
        Shaft.getMMKV().decodeString(KEY)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun remove(id: Long) {
        val remaining = peek().filterNot { it == id }
        write(remaining)
    }

    @Synchronized
    fun clear() {
        runCatching { Shaft.getMMKV().removeValueForKey(KEY) }
    }

    private fun write(ids: Collection<Long>) {
        runCatching { Shaft.getMMKV().encode(KEY, ids.joinToString(",")) }
            .onFailure { Timber.tag("ImportEnrichQueue").w(it, "写队列失败") }
    }
}
