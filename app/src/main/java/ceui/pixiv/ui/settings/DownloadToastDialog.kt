package ceui.pixiv.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.DialogDownloadToastsBinding
import ceui.lisa.utils.Local
import ceui.pixiv.download.toast.DownloadToastKind
import ceui.pixiv.download.toast.DownloadToasts
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogSwitchRow
import ceui.pixiv.witstudio.dialog.WitDialogView

/**
 * 「下载相关提示消息」统一入口弹窗。
 *
 * 原先只有一个总开关（`Settings.toastDownloadResult`）管着逐张完成 / 逐张失败 / aria2 /
 * 收藏后自动下载，其余下载提示（入队、批量汇总、逐篇进度）一律弹到底 —— 想安静一点的用户
 * 只能在「全弹」和「连失败都收不到」之间二选一。现在每条消息按 [DownloadToastKind] 各占
 * 一行开关，**默认全开**，安静哪条由用户自己挑；入口行摘要显示「已安静 N 条消息」。
 *
 * 版式对齐「过滤已收藏」/「预测性返回」：24dp 横向内边距、顶部 13sp 说明、扁平行（无分段底色）、
 * 行内标题左 / 开关右。改动在「确定」时才落盘，「取消」原样丢弃。
 */
object DownloadToastDialog {

    @JvmStatic
    @JvmOverloads
    fun show(context: Context, onChanged: Runnable? = null) {
        val switches = LinkedHashMap<DownloadToastKind, SwitchCompat>()
        object : WitDialog.CustomDialogBuilder(context) {
            override fun onCreateContent(
                dialog: WitDialog,
                parent: WitDialogView,
                context: Context,
            ): View {
                val content = DialogDownloadToastsBinding.inflate(
                    LayoutInflater.from(context), parent, false
                )
                fun rowsFor(group: DownloadToastKind.Group): LinearLayout =
                    if (group == DownloadToastKind.Group.IMAGE) content.dtImageRows
                    else content.dtNovelRows

                for (kind in DownloadToastKind.entries) {
                    val row = WitDialogSwitchRow.inflate(rowsFor(kind.group))
                    WitDialogSwitchRow.bind(
                        row, context.getString(kind.labelRes), null, !DownloadToasts.isQuiet(kind)
                    )
                    row.root.setOnClickListener { row.witRowSwitch.toggle() }
                    rowsFor(kind.group).addView(row.root)
                    switches[kind] = row.witRowSwitch
                }
                return wrapWithScroll(content.root)
            }
        }.setTitle(R.string.download_toast_entry_title)
            .addAction(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(android.R.string.ok) { dialog, _ ->
                DownloadToasts.applyChecked(switches.filterValues { it.isChecked }.keys)
                Local.setSettings(Shaft.sSettings)
                onChanged?.run()
                dialog.dismiss()
            }
            .show()
    }
}