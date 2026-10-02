package ceui.pixiv.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.DialogFilterBookmarkedBinding
import ceui.lisa.databinding.DialogFilterBookmarkedRowBinding
import ceui.lisa.utils.Local
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogView

/**
 * 「过滤已收藏」统一入口弹窗。
 *
 * 排行榜 / 动态页 / 搜索页三处原先各有一个独立开关行，这里收成同一个带标题的 witstudio 弹窗，
 * 三个开关分别读写 Shaft.sSettings 的 isFilterRankBookmarked / isDeleteStarIllust /
 * isSearchFilterBookmarked —— 旧字段一律保留，只是交互入口换了。
 *
 * 版式对齐「预测性返回」弹窗：24dp 横向内边距、顶部 13sp 说明、扁平行（无分段底色）、
 * 行内标题左 / 开关右。改动在「确定」时才落盘，「取消」原样丢弃。
 * 搜索页开关目前只持久化状态，搜索链路尚未接线。
 */
object FilterBookmarkedDialog {

    @JvmStatic
    @JvmOverloads
    fun show(context: Context, onChanged: Runnable? = null) {
        val switches = ArrayList<SwitchCompat>(3)
        object : WitDialog.CustomDialogBuilder(context) {
            override fun onCreateContent(
                dialog: WitDialog,
                parent: WitDialogView,
                context: Context,
            ): View {
                val content = DialogFilterBookmarkedBinding.inflate(
                    LayoutInflater.from(context), parent, false
                )
                fun addToggle(titleRes: Int, checked: Boolean) {
                    val row = DialogFilterBookmarkedRowBinding.inflate(
                        LayoutInflater.from(context), content.fbRows, false
                    )
                    row.fbRowTitle.setText(titleRes)
                    row.fbRowSwitch.contentDescription = context.getString(titleRes)
                    row.fbRowSwitch.isChecked = checked
                    row.root.setOnClickListener { row.fbRowSwitch.toggle() }
                    content.fbRows.addView(row.root)
                    switches.add(row.fbRowSwitch)
                }
                addToggle(
                    R.string.filter_bookmarked_rank, Shaft.sSettings.isFilterRankBookmarked
                )
                addToggle(
                    R.string.filter_bookmarked_following, Shaft.sSettings.isDeleteStarIllust
                )
                addToggle(
                    R.string.filter_bookmarked_search, Shaft.sSettings.isSearchFilterBookmarked
                )
                return wrapWithScroll(content.root)
            }
        }.setTitle(R.string.filter_bookmarked_title)
            .addAction(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(android.R.string.ok) { dialog, _ ->
                Shaft.sSettings.isFilterRankBookmarked = switches[0].isChecked
                Shaft.sSettings.isDeleteStarIllust = switches[1].isChecked
                Shaft.sSettings.isSearchFilterBookmarked = switches[2].isChecked
                Local.setSettings(Shaft.sSettings)
                onChanged?.run()
                dialog.dismiss()
            }
            .show()
    }
}
