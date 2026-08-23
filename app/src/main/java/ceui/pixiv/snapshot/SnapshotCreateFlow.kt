package ceui.pixiv.snapshot

import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 详情页「离线化」入口的唯一实现：选项弹窗 → 进度弹窗 → 生成 → 结果 toast。
 *
 * V3 详情页（[ceui.pixiv.ui.detail.ArtworkV3Fragment]）和经典详情页
 * （[ceui.lisa.fragments.FragmentIllust]）共用同一条链路，避免两套复制品各自漂移
 * （生成参数、进度文案、异常处理只有一份）。
 */
internal fun Fragment.showSnapshotCreateDialog(illust: Illust) {
    val builder = WitDialog.MultiCheckableDialogBuilder(requireContext())
    builder.setTitle(R.string.snapshot_create)
    builder.addItem(getString(R.string.snapshot_include_comments)) { _, _ -> }
    builder.addItem(getString(R.string.snapshot_include_original)) { _, _ -> }
    // 「使用原图」默认跟随全局的「展示原图」设置。
    builder.setCheckedItems(
        if (Shaft.sSettings.isShowOriginalPreviewImage()) intArrayOf(1) else intArrayOf()
    )
    builder.addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
    builder.addAction(R.string.snapshot_ok) { dialog, _ ->
        val checked = builder.checkedItemIndexes
        dialog.dismiss()
        startSnapshotGeneration(
            illust = illust,
            includeComments = checked.contains(0),
            includeOriginal = checked.contains(1),
        )
    }
    builder.show()
}

private fun Fragment.startSnapshotGeneration(
    illust: Illust,
    includeComments: Boolean,
    includeOriginal: Boolean,
) {
    val dialog = showSnapshotLoadingDialog(getString(R.string.snapshot_preparing))
    val appContext = requireContext().applicationContext
    lifecycleScope.launch {
        try {
            SnapshotGenerator.generate(
                context = appContext,
                illust = illust,
                includeComments = includeComments,
                includeOriginal = includeOriginal,
                // generate 整段跑在 IO 上，进度回调自己切回主线程刷文案，
                // 不再借 requireActivity().runOnUiThread —— 那条路在 fragment 已 detach 时会抛。
                onProgress = { message ->
                    withContext(Dispatchers.Main) {
                        dialog.findViewById<TextView>(R.id.loading_message)?.text = message
                    }
                },
            )
            dialog.dismiss()
            Common.showToast(getString(R.string.snapshot_generate_success))
        } catch (ce: CancellationException) {
            dialog.dismiss()
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "[Snapshot] generate failed, illustId=%d", illust.id)
            dialog.dismiss()
            Common.showToast(getString(R.string.snapshot_generate_failed, e.message ?: ""))
        }
    }
}

/** 快照相关的统一 loading 弹窗（生成 / 导入 / 批量导出共用）。 */
internal fun Fragment.showSnapshotLoadingDialog(message: String): WitDialog {
    val dialog = WitDialog.CustomDialogBuilder(requireContext())
        .setLayout(R.layout.dialog_snapshot_loading)
        .setCancelable(false)
        .show()
    dialog.findViewById<TextView>(R.id.loading_message)?.text = message
    return dialog
}
