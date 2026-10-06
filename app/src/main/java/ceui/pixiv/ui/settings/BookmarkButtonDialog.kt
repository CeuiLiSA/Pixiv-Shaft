package ceui.pixiv.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.DialogBookmarkButtonBinding
import ceui.lisa.utils.Local
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogSwitchRow
import ceui.pixiv.witstudio.dialog.WitDialogView

/**
 * 「作品卡片上显示收藏按钮」统一入口弹窗。
 *
 * 每个出现瀑布流卡+收藏爱心的页面原先要么各有一个独立开关（收藏页 / 收藏库 / 小组件），
 * 要么根本没有开关；这里收成同一个带标题的 witstudio 弹窗，逐页一个开关。
 *
 * 真源是 [ceui.lisa.utils.Settings.getHiddenBookmarkSurfaces]（空集 = 全部显示），
 * 行序与分组直接来自 [BookmarkSurface] 的声明序。版式对齐「预测性返回」弹窗：24dp 横向
 * 内边距、顶部 13sp 说明、两个分组标题、扁平行（无分段底色）。改动在「确定」时才落盘，
 * 「取消」原样丢弃。
 */
object BookmarkButtonDialog {

    @JvmStatic
    @JvmOverloads
    fun show(context: Context, onChanged: Runnable? = null) {
        // 与 switches 同序：确定时按下标回写，不靠视图树反查
        val surfaces = ArrayList<BookmarkSurface>()
        val switches = ArrayList<SwitchCompat>()
        object : WitDialog.CustomDialogBuilder(context) {
            override fun onCreateContent(
                dialog: WitDialog,
                parent: WitDialogView,
                context: Context,
            ): View {
                val content = DialogBookmarkButtonBinding.inflate(
                    LayoutInflater.from(context), parent, false
                )
                fun fill(container: LinearLayout, group: List<BookmarkSurface>) {
                    for (surface in group) {
                        val row = WitDialogSwitchRow.inflate(container)
                        WitDialogSwitchRow.bind(
                            row,
                            context.getString(surface.labelRes),
                            null,
                            Shaft.sSettings.isBookmarkSurfaceVisible(surface),
                        )
                        row.root.setOnClickListener { row.witRowSwitch.toggle() }
                        container.addView(row.root)
                        surfaces.add(surface)
                        switches.add(row.witRowSwitch)
                    }
                }
                // 「常用」13 行当帧建；「更多」12 行等弹窗第一帧画完再补。
                // 25 行挤在同一帧里建，点下去要等全部 inflate 完才见得到弹窗（主线程 100ms 量级）；
                // 拆开后点击那一帧只建 13 行，弹窗先出来，剩下 12 行补在视野之外。
                fill(content.bbCommonRows, BookmarkSurface.commonGroup())
                content.bbMoreHeader.isVisible = false
                content.root.doOnLayout {
                    // 第一帧的 layout 已经跑完、draw 还没开始 —— 排到下一帧，第一帧就能先画出去。
                    // 别图省事写成 doOnLayout { fill(...) }：那还是在第一帧的 traversal 里跑，
                    // 会把这一帧的 draw 拖到 12 行建完之后，等于没延迟。
                    content.bbMoreRows.postOnAnimation {
                        // 弹窗可能在补之前就被关掉：那就不补，确定时也只落盘已经展示出来的那 13 项。
                        if (!content.root.isAttachedToWindow) return@postOnAnimation
                        fill(content.bbMoreRows, BookmarkSurface.moreGroup())
                        content.bbMoreHeader.isVisible = true
                    }
                }
                return wrapWithScroll(content.root)
            }
        }.setTitle(R.string.bookmark_button_title)
            .addAction(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(android.R.string.ok) { dialog, _ ->
                surfaces.forEachIndexed { index, surface ->
                    Shaft.sSettings.setBookmarkSurfaceVisible(surface, switches[index].isChecked)
                }
                Local.setSettings(Shaft.sSettings)
                onChanged?.run()
                dialog.dismiss()
            }
            .show()
    }
}