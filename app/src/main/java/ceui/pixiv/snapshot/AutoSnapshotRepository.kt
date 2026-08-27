package ceui.pixiv.snapshot

import android.content.Context
import java.io.File

/**
 * 自动快照仓储：与正式快照共用 ShaftSnapshots 根目录，但只认 auto_manifest.json。
 *
 * 自动快照没有正式 manifest.json，因此 SnapshotRepository.list() 天然忽略；
 * 转正后由 SnapshotPromoter 写入 manifest.json 并删除 auto_manifest.json。
 */
object AutoSnapshotRepository {

    /** 本次试验硬编码的自动快照总大小上限。 */
    private const val AUTO_SNAPSHOT_MAX_BYTES = 200L * 1024 * 1024

    fun listAuto(context: Context): List<AutoSnapshotSummary> {
        return SnapshotRepository.root(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { snapshotDir ->
                // 已经是正式快照（manifest.json 存在）就不再作为自动快照展示，避免转正后重复卡。
                if (File(snapshotDir, SNAPSHOT_MANIFEST).isFile) return@mapNotNull null
                val manifest = SnapshotValidator.readJson<AutoSnapshotManifest>(
                    File(snapshotDir, AUTO_SNAPSHOT_MANIFEST)
                ) ?: return@mapNotNull null
                // 与正式库同一不变式：目录名必须等于 manifest.snapshotId。
                if (snapshotDir.name != manifest.snapshotId) return@mapNotNull null

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
                val coverFile = manifest.coverPath?.let { rel ->
                    runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.takeIf { f -> f.isFile }
                }
                AutoSnapshotSummary(manifest, fileCount, totalSize, coverFile)
            }
            ?.sortedByDescending { it.manifest.createdAt }
            ?: emptyList()
    }

    fun readAutoManifest(context: Context, snapshotId: String): AutoSnapshotManifest? =
        SnapshotValidator.readJson(File(SnapshotRepository.dir(context, snapshotId), AUTO_SNAPSHOT_MANIFEST))

    /**
     * 复用 SnapshotRepository.loadViewerData，只把 manifest 来源换成 auto_manifest.json。
     * 内存中转换成 SnapshotManifest 仅用于渲染，不写盘、不保留自动来源痕迹。
     */
    fun loadAutoViewerData(context: Context, snapshotId: String): SnapshotViewerData {
        val manifest = readAutoManifest(context, snapshotId)
            ?: throw SnapshotException("自动快照不存在或 auto_manifest 损坏: $snapshotId")
        return SnapshotRepository.loadViewerData(context, snapshotId, manifest.toSnapshotManifest())
    }

    fun deleteAuto(context: Context, snapshotId: String): Boolean =
        SnapshotRepository.delete(context, snapshotId)

    /** 统计所有自动快照大小，超过硬编码阈值后按 createdAt 从旧到新淘汰，直到总大小低于阈值。 */
    fun enforceAutoQuota(context: Context) {
        var autos = listAuto(context)
        while (autos.isNotEmpty()) {
            val total = autos.sumOf { it.totalSize }
            if (total <= AUTO_SNAPSHOT_MAX_BYTES) break
            val oldest = autos.minByOrNull { it.manifest.createdAt } ?: break
            deleteAuto(context, oldest.manifest.snapshotId)
            autos = listAuto(context)
        }
    }
}