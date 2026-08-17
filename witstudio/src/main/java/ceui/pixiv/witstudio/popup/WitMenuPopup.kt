package ceui.pixiv.witstudio.popup

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 锚定下拉菜单，替代 `ceui.lisa.utils.QMUIMenuPopup`（原本是 androidx `PopupMenu` 的替代品，
 * 目的是统一项目里的下拉菜单视觉）。
 *
 * 相比原实现有一处**有意的视觉升级**：底色不再写死 `@color/fragment_center`
 *（那个值夜间恒为深灰、不跟主题走），改用 [WitPopup] 的默认 `V3Palette.cardFill`，
 * 于是十档主题和自定义色都能带上。
 */
public object WitMenuPopup {

    private const val WIDTH_DP = 180
    private const val MAX_HEIGHT_DP = 320
    private const val EDGE_PROTECTION_DP = 12

    public fun interface OnItemSelected {
        public fun onItemSelected(index: Int, text: CharSequence)
    }

    @JvmStatic
    public fun show(
        context: Context,
        anchor: View,
        titles: Array<CharSequence>,
        listener: OnItemSelected,
    ): WitPopup = show(context, anchor, titles.toList(), listener)

    @JvmStatic
    public fun show(
        context: Context,
        anchor: View,
        titles: List<CharSequence>,
        listener: OnItemSelected,
    ): WitPopup {
        val adapter = ArrayAdapter(context, R.layout.wit_item_menu, titles)
        // 点击回调里要 dismiss 自己，但 popup 实例在 lambda 创建时还不存在 —— 用一格数组接住，
        // 同原 QMUIMenuPopup 的写法。
        val holder = arrayOfNulls<WitPopup>(1)
        val popup = WitPopups.listPopup(
            context,
            WitDisplay.dp2px(context, WIDTH_DP),
            WitDisplay.dp2px(context, MAX_HEIGHT_DP),
            adapter,
        ) { _, _, position, _ ->
            listener.onItemSelected(position, titles[position])
            holder[0]?.dismiss()
        }
        popup.preferredDirection(WitPopup.DIRECTION_BOTTOM)
            .shadow(true)
            .animStyle(WitPopup.ANIM_GROW_FROM_CENTER)
            .edgeProtection(WitDisplay.dp2px(context, EDGE_PROTECTION_DP))
            .show(anchor)
        holder[0] = popup
        return popup
    }
}
