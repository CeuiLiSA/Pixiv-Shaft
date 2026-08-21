package ceui.pixiv.snapshot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ceui.lisa.models.IllustsBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class SnapshotSummary(
    val manifest: SnapshotManifest,
    val fileCount: Int,
    val totalSize: Long,
    val coverFile: File?,
)

data class SnapshotViewerData(
    val snapshotDir: File,
    val manifest: SnapshotManifest,
    val illust: IllustsBean,
    val assets: Map<String, String>,
    val comments: SnapshotComments?,
) {
    fun resolve(url: String?): File? {
        if (url.isNullOrBlank()) return null
        val rel = assets[url] ?: return null
        return runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.takeIf { it.isFile }
    }
}

/**
 * 私有快照库：
 * files/ShaftSnapshots/<snapshotId>/
 *
 * 目录即快照，ZIP/SAF 只做一次性导入导出，不持久化任何 URI。
 */
object SnapshotRepository {

    fun root(context: Context): File = File(context.filesDir, SNAPSHOT_ROOT_DIR).apply { mkdirs() }

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
                val coverFile = manifest.coverPath?.let { rel -> runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.takeIf { f -> f.isFile } }
                var fileCount = 0
                var totalSize = 0L
                snapshotDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        fileCount++
                        totalSize += file.length()
                    }
                }
                SnapshotSummary(manifest, fileCount, totalSize, coverFile)
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
        val illust = SnapshotValidator.readJson<IllustsBean>(File(snapshotDir, SNAPSHOT_ILLUST_JSON))
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
            val safeTitle = manifest.title
                ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: "snapshot"
            val fileName = "${safeTitle}_${manifest.illustId}$SNAPSHOT_EXTENSION"
            val file = parent.createFile("application/zip", fileName)
                ?: throw SnapshotException("无法在所选文件夹创建 $fileName")
            val out = context.contentResolver.openOutputStream(file.uri)
                ?: throw SnapshotException("无法写入 $fileName")
            out.use { SnapshotArchive.zipDirectory(snapshotDir, it) }
        }

    suspend fun import(context: Context, uri: Uri): SnapshotManifest = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "snapshot_import_${System.currentTimeMillis()}$SNAPSHOT_EXTENSION")
        val staging = File(context.cacheDir, "snapshot_staging_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { out -> input.copyTo(out) }
            } ?: throw SnapshotException("无法读取所选文件")
            staging.mkdirs()
            FileInputStream(tempZip).use { SnapshotArchive.unzip(it, staging) }
            val manifest = SnapshotValidator.readJson<SnapshotManifest>(File(staging, SNAPSHOT_MANIFEST))
                ?: throw SnapshotException("不是有效的 .shaftsnap 快照：缺少 manifest.json")
            if (manifest.schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
                throw SnapshotException("不支持的快照版本: ${manifest.schemaVersion}")
            }
            requireSnapshotId(manifest.snapshotId)
            SnapshotValidator.validate(staging, manifest)
            val target = dir(context, manifest.snapshotId)
            val backup = File(target.parentFile, "${target.name}.old_${System.currentTimeMillis()}")
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw SnapshotException("无法替换已有快照: ${manifest.snapshotId}")
                }
            }
            target.mkdirs()
            try {
                staging.copyRecursively(target, overwrite = true)
                backup.deleteRecursively()
                SnapshotRuntimeCache.remove(manifest.snapshotId)
                manifest
            } catch (e: Exception) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw e
            }
        } finally {
            tempZip.delete()
            staging.deleteRecursively()
        }
    }
}