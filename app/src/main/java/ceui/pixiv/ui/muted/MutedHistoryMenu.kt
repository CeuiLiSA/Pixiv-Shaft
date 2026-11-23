package ceui.pixiv.ui.muted

import android.view.View
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.widget.WitLiquidGlassMenu

/**
 * 屏蔽记录页右上角更多菜单（液态玻璃风格）。
 * 用户要求：无选项图标。
 */
object MutedHistoryMenu {

    @JvmStatic
    fun show(
        anchor: View,
        onExport: Runnable,
        onImport: Runnable,
    ) {
        val context = anchor.context
        val primary = Common.resolveThemeAttribute(context, androidx.appcompat.R.attr.colorPrimary)
        WitLiquidGlassMenu.show(anchor) {
            borderless = true
            startPlatterColor = primary
            anchorIconRes = ceui.pixiv.witstudio.R.drawable.wit_ic_more_vert
            item(context.getString(R.string.mute_records_export)) {
                onExport.run()
            }
            item(context.getString(R.string.mute_records_import)) {
                onImport.run()
            }
        }
    }
}
