package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 自动快照转正：在同一个目录里写入正式 manifest.json、删除 auto_manifest.json。
 *
 * 转正是完整流程：校验资源 → 写正式 Manifest → 清掉自动来源 → 刷新缓存 →
 * 列表重载。转正后不保留任何“来自自动快照”的痕迹。
 */
object SnapshotPromoter {

    suspend fun promote(context: Context, autoId: String): SnapshotManifest = withContext(Dispatchers.IO) {
        AutoSnapshotRepository.mutationLock.withLock {
            promoteLocked(context.applicationContext, autoId)
        }
    }

    /** 调用方持有仓储锁，校验、清单提交与索引缓存更新构成一次本地事务。 */
    private fun promoteLocked(appContext: Context, autoId: String): SnapshotManifest {
        val snapshotDir = SnapshotRepository.dir(appContext, autoId)
        val auto = AutoSnapshotRepository.readAutoManifest(appContext, autoId)
            ?: throw SnapshotException("自动快照不存在或 auto_manifest 损坏: $autoId")
        val formal = auto.toSnapshotManifest()

        // 必须先做完整资源校验，不能把残缺目录变成正式快照。
        SnapshotValidator.validateContents(snapshotDir, formal)

        // 在同目录写完后再原子替换，写入失败不能留下半份正式清单遮住自动快照。
        val pending = File.createTempFile("manifest-", ".tmp", snapshotDir)
        try {
            pending.writeText(Shaft.sGson.toJson(formal))
            if (!pending.renameTo(File(snapshotDir, SNAPSHOT_MANIFEST))) {
                throw SnapshotException("无法提交正式快照清单: $autoId")
            }
        } finally {
            pending.delete()
        }
        File(snapshotDir, AUTO_SNAPSHOT_MANIFEST).delete()
        // 用完整清单登记：即使首次索引扫描尚未完成，转正后的来源与创建时间也不会丢。
        SnapshotIndex.onSnapshotWritten(formal.illustId, autoId, isAuto = false, formal.createdAt)

        // 缓存先尝试更新；失败降级为移除，避免继续读到转正前的 auto 数据。
        runCatching {
            val fresh = SnapshotRepository.loadViewerData(appContext, autoId)
            SnapshotRuntimeCache.put(autoId, fresh)
        }.onFailure {
            SnapshotRuntimeCache.remove(autoId)
        }

        return formal
    }
}
