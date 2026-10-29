package ceui.pixiv.snapshot

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.pixiv.ui.navigation.TemplateRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 打开卡片上那枚快照标记指向的离线详情页。瀑布流卡（[ceui.pixiv.ui.common.staggerIllustRenderer]）
 * 与浏览记录卡（[ceui.lisa.fragments.historyIllustRenderer]）共用这一条路径 —— 两处各抄一遍
 * 早晚会分叉（一个认 V3 路由、一个写死经典页）。
 *
 * 流程与快照管理页 [SnapshotListFragment] 一致：先把快照读进 [SnapshotRuntimeCache]（详情页一进去
 * 就同步取，省掉一次等待），再按「是否启用 V3 详情页」选路由。
 *
 * 读失败 = 目录被删 / manifest 损坏：顺手把索引里那条过期记录摘掉 —— 卡片下次不再显示这枚
 * 点开就报错的标记。
 *
 * @param owner 调用方的生命周期（Fragment 传 `viewLifecycleOwner`）：页面销毁后不再启动 Activity。
 */
fun openSnapshotViewer(context: Context, owner: LifecycleOwner, entry: SnapshotIndex.Entry) {
    val appContext = context.applicationContext
    owner.lifecycleScope.launch {
        try {
            withContext(Dispatchers.IO) {
                if (SnapshotRuntimeCache.get(entry.snapshotId) == null) {
                    val data = if (entry.isAuto) {
                        AutoSnapshotRepository.loadAutoViewerData(appContext, entry.snapshotId)
                    } else {
                        SnapshotRepository.loadViewerData(appContext, entry.snapshotId)
                    }
                    SnapshotRuntimeCache.put(entry.snapshotId, data)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "[Snapshot] open from card failed, id=%s", entry.snapshotId)
            SnapshotIndex.onSnapshotDeleted(entry.snapshotId)
            Common.showToast(appContext.getString(R.string.snapshot_open_failed, e.message ?: ""))
            return@launch
        }
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(
            TemplateActivity.EXTRA_FRAGMENT,
            if (Shaft.sSettings.isUseArtworkV3()) {
                TemplateRoute.SNAPSHOT_VIEW.key
            } else {
                TemplateRoute.SNAPSHOT_VIEW_CLASSIC.key
            },
        )
        intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID, entry.snapshotId)
        intent.putExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, entry.isAuto)
        context.startActivity(intent)
    }
}