package ceui.pixiv.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat

/**
 * 品牌色 toolbar(toolbar_layout.xml 那套:?attr/colorPrimary 背景 + 白字)上的 menu icon
 * 统一成白色,disabled 时半透明。项目里的 V3 菜单 icon(ic_v3_export_24 / ic_select_all_24 …)
 * fillColor 焊死的是 v3_text_1,那是给 v3_bg 浅底 sheet 用的,放到品牌色 toolbar 上白天会是
 * 深色块。inflateMenu / setIcon 之后调一次即可。
 *
 * 走 MenuItemCompat:MenuItem.setIconTintList 是 API 26 才进 framework 的接口方法,
 * 直接赋值在 API 24/25 会 NoSuchMethodError。
 */
fun Toolbar.tintMenuIconsWhite() {
    val menu = menu
    for (i in 0 until menu.size()) {
        MenuItemCompat.setIconTintList(menu.getItem(i), WHITE_MENU_ICON_TINT)
    }
}

private val WHITE_MENU_ICON_TINT = ColorStateList(
    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
    intArrayOf(Color.argb(0x80, 0xFF, 0xFF, 0xFF), Color.WHITE),
)
