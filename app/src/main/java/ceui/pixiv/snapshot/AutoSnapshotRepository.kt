package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 自动快照仓储：与正式快照共用 ShaftSnapshots 根目录，但只认 auto_manifest.json。
 *
 * 自动快照没有正式 manifest.json，因此 SnapshotRepository.list() 天然忽略；
 * 转正后由 SnapshotPromoter 写入 manifest.json 并删除 auto_manifest.json。
 */
object AutoSnapshotRepository {

    // 转正与自动删除共用临界区，身份检查和目录修改之间不能再被另一方插入。
    internal val mutationLock = Mutex()

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

    suspend fun deleteAuto(context: Context, snapshotId: String): Boolean {
        val snapshotDir = SnapshotRepository.dir(context, snapshotId)
        return mutationLock.withLock {
            // 清理候选、管理页卡片都可能过期；正式快照不能从自动快照入口删除。
            if (File(snapshotDir, SNAPSHOT_MANIFEST).isFile) return@withLock false
            SnapshotRepository.delete(context, snapshotId)
        }
    }

    /** 只接收生成器新建、尚未发布的目录；最终复核与失败回滚都在转正可见之前完成。 */
    internal suspend fun publishAuto(
        context: Context,
        manifest: AutoSnapshotManifest,
        reusedCachedOriginal: Boolean,
        maxBytes: () -> Long = { AutoSnapshotQuota.maxBytesForLimit(Shaft.sSettings.autoSnapshotMaxMb) },
    ): AutoSnapshotManifest {
        val dir = SnapshotRepository.dir(context, manifest.snapshotId)
        try {
            mutationLock.lock()
        } catch (ce: CancellationException) {
            // 等锁时尚未写入自动清单，目录还未发布，取消只需回收本次生成的资源。
            dir.deleteRecursively()
            throw ce
        }
        try {
            val file = File(dir, AUTO_SNAPSHOT_MANIFEST)
            file.writeText(Shaft.sGson.toJson(manifest))
            if (manifest.totalSize + file.length() > maxBytes()) {
                throw AutoSnapshotQuotaExceededException(reusedCachedOriginal)
            }
            SnapshotIndex.onSnapshotWritten(manifest.illustId, manifest.snapshotId, isAuto = true, manifest.createdAt)
            return manifest
        } catch (e: Exception) {
            // 必须在释放锁前回滚，不能让转正读到即将被回收的自动清单。
            dir.deleteRecursively()
            throw e
        } finally {
            mutationLock.unlock()
        }
    }

    /** 统计所有自动快照大小，超过当前用户设置的上限后按 createdAt 从旧到新淘汰，直到总大小低于阈值。 */
    suspend fun enforceAutoQuota(context: Context) {
        enforceAutoQuota(context, AutoSnapshotQuota.maxBytesForLimit(Shaft.sSettings.autoSnapshotMaxMb))
    }

    /** 允许调用方直接传入字节上限；最右端哨兵已由 [AutoSnapshotQuota] 换算为不限制。 */
    suspend fun enforceAutoQuota(context: Context, maxBytes: Long) {
        var autos = listAuto(context)
        while (autos.isNotEmpty()) {
            val total = autos.sumOf { it.totalSize }
            if (total <= maxBytes) break
            // 单份已超额时先移除它；否则按 LRU 清旧快照。避免新来的巨型快照清空整个旧库。
            val victim = autos.firstOrNull { it.totalSize > maxBytes }
                ?: autos.minByOrNull { it.manifest.createdAt }
                ?: break
            val deleted = deleteAuto(context, victim.manifest.snapshotId)
            autos = listAuto(context)
            // 已转正或被其他入口删除就重新计算配额；仍在自动库中的删除失败项必须退出，
            // 否则下一轮仍会选中它，陷入永不推进的全库扫描。
            if (!deleted && autos.any { it.manifest.snapshotId == victim.manifest.snapshotId }) break
        }
    }
}
