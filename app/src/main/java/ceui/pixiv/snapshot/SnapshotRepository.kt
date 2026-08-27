package ceui.pixiv.snapshot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ceui.loxia.Illust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SnapshotSummary(
    val manifest: SnapshotManifest,
    val fileCount: Int,
    val totalSize: Long,
    val coverFile: File?,
)

data class SnapshotViewerData(
    val snapshotDir: File,
    val manifest: SnapshotManifest,
    val illust: Illust,
    val assets: Map<String, String>,
    val comments: SnapshotComments?,
) {
    /**
     * 第 [index] 页在快照里的那份文件 + 它的相对路径。
     *
     * 快照一页只存一份文件，存的是 large 还是 original 由生成时的选项决定；而消费方按哪一档
     * 分辨率来问是它自己决定的（大图页恒问 ORIGINAL）。所以这里把该页**所有尺寸变体**依次拿去
     * assets 里查，命中即用——这是「第 i 页的本地文件是哪个」的唯一答案，生成、校验、渲染、
     * 大图页都只许问它，不许各自再拼一遍 URL。
     *
     * p0 额外兜一层 manifest.coverPath：老快照的 assets 里可能只有当初那一个 URL。
     */
    private fun pageAsset(index: Int): Pair<File, String>? {
        val rels = illust.snapshotPageVariantUrls(index).mapNotNull { assets[it] } +
            listOfNotNull(manifest.coverPath?.takeIf { index == 0 })
        rels.forEach { rel ->
            val file = runCatching { safeResolve(snapshotDir, rel) }.getOrNull()
            if (file != null && file.isFile) return file to rel
        }
        return null
    }

    fun pageFile(index: Int): File? = pageAsset(index)?.first

    fun pageLocalUrl(index: Int): String? =
        pageAsset(index)?.let { (_, rel) -> snapshotLocalUrl(manifest.snapshotId, rel) }
}

/**
 * 私有快照库：
 * files/ShaftSnapshots/<snapshotId>/
 *
 * 目录即快照，ZIP/SAF 只做一次性导入导出，不持久化任何 URI。
 */
object SnapshotRepository {

    /**
     * 快照库根目录。**只算路径，不建目录**：它在 Glide 的图片加载路径上被调用
     * （见 snapshotAssetFile），顺手 mkdirs 等于每加载一张快照图多一次 stat + 可能的 mkdir
     * 系统调用。建目录是写入方的事，见 [createSnapshotDir] / [import]。
     */
    fun root(context: Context): File = File(context.filesDir, SNAPSHOT_ROOT_DIR)

    fun dir(context: Context, snapshotId: String): File = File(root(context), requireSnapshotId(snapshotId))

    fun createSnapshotDir(context: Context, snapshotId: String): File {
        val target = dir(context, snapshotId)
        target.mkdirs()
        return target
    }

    fun list(context: Context): List<SnapshotSummary> {
        val root = root(context)
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { snapshotDir ->
                val manifest = SnapshotValidator.readJson<SnapshotManifest>(File(snapshotDir, SNAPSHOT_MANIFEST))
                    ?: return@mapNotNull null
                // 「目录名即快照 ID」是这个库的根本不变式：卡片上的所有操作(打开/导出/删除)
                // 都拿 manifest.snapshotId 反解目录。名字对不上的目录(导入中途掉电留下的
                // 备份等)必须当作不存在，否则会出现一张指向别人的重复卡片,删它删掉的是正主。
                if (snapshotDir.name != manifest.snapshotId) return@mapNotNull null
                // 早期快照/手改 manifest 可能没记 R 级和页数；用 illust.json 回补，
                // 保证老卡片也能显示左上角 R 级与 P 角标（fileCount 是整目录文件数，不是页数）。
                val displayManifest = manifest.backfillDisplayFields(snapshotDir)
                val coverFile = displayManifest.coverPath?.let { rel -> runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.takeIf { f -> f.isFile } }
                // 文件数/体积生成时就写进 manifest 了，列表页(三个 Tab、每次 onResume 都刷)
                // 没必要再把每个快照目录整个 walk 一遍 —— 那是 O(全库文件数) 次 stat。
                // 只有老快照/manifest 没记的情况才退回真扫。
                var fileCount = manifest.fileCount
                var totalSize = manifest.totalSize
                if (fileCount <= 0 || totalSize <= 0L) {
                    fileCount = 0
                    totalSize = 0L
                    snapshotDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            fileCount++
                            totalSize += file.length()
                        }
                    }
                }
                SnapshotSummary(displayManifest, fileCount, totalSize, coverFile)
            }
            ?.sortedByDescending { it.manifest.createdAt }
            ?: emptyList()
    }

    fun readManifest(context: Context, snapshotId: String): SnapshotManifest? =
        SnapshotValidator.readJson(File(dir(context, snapshotId), SNAPSHOT_MANIFEST))

    fun delete(context: Context, snapshotId: String): Boolean {
        val target = dir(context, snapshotId)
        val ok = if (target.isDirectory) target.deleteRecursively() else false
        if (ok) SnapshotRuntimeCache.remove(snapshotId)
        return ok
    }

    fun loadViewerData(context: Context, snapshotId: String): SnapshotViewerData {
        val snapshotDir = dir(context, snapshotId)
        val manifest = SnapshotValidator.readJson<SnapshotManifest>(File(snapshotDir, SNAPSHOT_MANIFEST))
            ?: throw SnapshotException("快照不存在或 manifest 损坏: $snapshotId")
        return loadViewerData(context, snapshotId, manifest)
    }

    internal fun loadViewerData(
        context: Context,
        snapshotId: String,
        manifest: SnapshotManifest,
    ): SnapshotViewerData {
        val snapshotDir = dir(context, snapshotId)
        val illust = SnapshotValidator.readJson<Illust>(File(snapshotDir, SNAPSHOT_ILLUST_JSON))
            ?: throw SnapshotException("快照缺少 illust.json: $snapshotId")
        val assets = SnapshotValidator.readJson<SnapshotAssets>(File(snapshotDir, SNAPSHOT_ASSETS_JSON))?.assets ?: emptyMap()
        val comments = if (manifest.includeComments) {
            SnapshotValidator.readJson<SnapshotComments>(File(snapshotDir, SNAPSHOT_COMMENTS_JSON))
        } else null
        return SnapshotViewerData(snapshotDir, manifest, illust, assets, comments)
    }

    suspend fun export(context: Context, snapshotId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val snapshotDir = dir(context, snapshotId)
        if (!snapshotDir.isDirectory) throw SnapshotException("快照不存在: $snapshotId")
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw SnapshotException("无法打开导出位置")
        out.use { SnapshotArchive.zipDirectory(snapshotDir, it) }
    }

    /** 批量导出到 SAF 目录：在所选文件夹里为每个快照创建一个 .shaftsnap 文件。 */
    suspend fun exportToDirectory(context: Context, snapshotId: String, treeUri: Uri) =
        withContext(Dispatchers.IO) {
            val snapshotDir = dir(context, snapshotId)
            if (!snapshotDir.isDirectory) throw SnapshotException("快照不存在: $snapshotId")
            val manifest = readManifest(context, snapshotId)
                ?: throw SnapshotException("快照 manifest 损坏: $snapshotId")
            val parent = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw SnapshotException("无法打开所选文件夹")
            val fileName = manifest.safeExportFileName()
            val file = parent.createFile("application/zip", fileName)
                ?: throw SnapshotException("无法在所选文件夹创建 $fileName")
            val out = context.contentResolver.openOutputStream(file.uri)
                ?: throw SnapshotException("无法写入 $fileName")
            out.use { SnapshotArchive.zipDirectory(snapshotDir, it) }
        }

    suspend fun import(context: Context, uri: Uri): SnapshotManifest = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "snapshot_staging_${System.currentTimeMillis()}")
        sweepStaleBackups(context)
        try {
            staging.mkdirs()
            // 直接从 SAF 流解到 staging：先落一份完整 ZIP 到 cacheDir 再解，等于同一份数据
            // 写两遍、峰值还要多占一个快照大小的可用空间(原图快照动辄几百 MB)，而 ZipInputStream
            // 本来就是顺序读，落盘那一趟没有换来任何东西。
            context.contentResolver.openInputStream(uri)?.use { input ->
                SnapshotArchive.unzip(input, staging)
            } ?: throw SnapshotException("无法读取所选文件")
            val manifest = SnapshotValidator.readJson<SnapshotManifest>(File(staging, SNAPSHOT_MANIFEST))
                ?: throw SnapshotException("不是有效的 .shaftsnap 快照：缺少 manifest.json")
            if (manifest.schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
                throw SnapshotException("不支持的快照版本: ${manifest.schemaVersion}")
            }
            requireSnapshotId(manifest.snapshotId)
            SnapshotValidator.validate(staging, manifest)
            val target = dir(context, manifest.snapshotId)
            // 首次导入时快照库根目录还不存在(root() 刻意不建目录),不先建出来的话下面那次
            // rename 必然失败、白白回落到全量拷贝。
            root(context).mkdirs()
            // 备份目录名带 BACKUP_PREFIX：合法快照 ID 只允许 [A-Za-z0-9_-]（见 requireSnapshotId），
            // 所以带点前缀的名字永远不会与真快照目录撞车，也就永远不会被 list() 当成一张卡片。
            val backup = File(target.parentFile, "$BACKUP_PREFIX${target.name}_${System.currentTimeMillis()}")
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw SnapshotException("无法替换已有快照: ${manifest.snapshotId}")
                }
            }
            try {
                // cacheDir 与 filesDir 同在 app 数据目录下(同一挂载点),rename 就是改个目录项,
                // 零字节拷贝。copyRecursively 等于把刚解压出来的这一份再写一遍 —— 几百 MB 的
                // 原图快照会白白多花一倍时间、峰值还要多占一个快照大小的可用空间,正是
                // 「导入不再先落一份完整 ZIP」那次要避免的同一件事。
                // rename 失败(理论上的分区差异)再回落到老路子。
                if (!staging.renameTo(target)) {
                    target.mkdirs()
                    staging.copyRecursively(target, overwrite = true)
                }
                backup.deleteRecursively()
                SnapshotRuntimeCache.remove(manifest.snapshotId)
                manifest
            } catch (e: Exception) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw e
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 清掉上一次导入没走完（进程被杀 / 掉电）留在库里的备份目录。
     * 只在导入入口扫一次即可：它是唯一会产生备份目录的地方。
     */
    private fun sweepStaleBackups(context: Context) {
        root(context).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * 老快照的 manifest 可能没记 R 级/页数；从快照自带的 illust.json 回补显示字段。
     * 只影响列表展示、不落盘，避免在只读 list() 里产生写副作用。
     */
    private fun SnapshotManifest.backfillDisplayFields(snapshotDir: File): SnapshotManifest {
        if (xRestrict != null && pageCount != null && pageCount > 0) return this
        val illust = SnapshotValidator.readJson<Illust>(File(snapshotDir, SNAPSHOT_ILLUST_JSON)) ?: return this
        return copy(
            xRestrict = xRestrict ?: illust.x_restrict,
            pageCount = if (pageCount != null && pageCount > 0) pageCount else illust.page_count.coerceAtLeast(1)
        )
    }

    private const val BACKUP_PREFIX = ".backup_"
}