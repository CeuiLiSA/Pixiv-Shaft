package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自动快照转正：在同一个目录里写入正式 manifest.json、删除 auto_manifest.json。
 *
 * 转正是完整流程：校验资源 → 写正式 Manifest → 清掉自动来源 → 刷新缓存 →
 * 列表重载。转正后不保留任何“来自自动快照”的痕迹。
 */
object SnapshotPromoter {

    suspend fun promote(context: Context, autoId: String): SnapshotManifest = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val snapshotDir = SnapshotRepository.dir(appContext, autoId)
        val auto = AutoSnapshotRepository.readAutoManifest(appContext, autoId)
            ?: throw SnapshotException("自动快照不存在或 auto_manifest 损坏: $autoId")
        val formal = auto.toSnapshotManifest()

        // 必须先做完整资源校验，不能把残缺目录变成正式快照。
        SnapshotValidator.validateContents(snapshotDir, formal)

        File(snapshotDir, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(formal))
        File(snapshotDir, AUTO_SNAPSHOT_MANIFEST).delete()

        // 缓存先尝试更新；失败降级为移除，避免继续读到转正前的 auto 数据。
        runCatching {
            val fresh = SnapshotRepository.loadViewerData(appContext, autoId)
            SnapshotRuntimeCache.put(autoId, fresh)
        }.onFailure {
            SnapshotRuntimeCache.remove(autoId)
        }

        formal
    }
}