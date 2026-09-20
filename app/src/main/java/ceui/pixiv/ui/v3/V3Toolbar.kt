package ceui.pixiv.ui.v3

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.ViewGroupCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.fragments.BaseFragment

/**
 * 独立 V3 页面的顶栏接线：`<include layout="@layout/toolbar_layout" />` 之后调一次。
 *
 * 顶栏自己吃状态栏 inset（品牌色铺到屏幕顶），API 29 及以下靠
 * [ViewGroupCompat.installCompatInsetsDispatch] 保证内容区兄弟节点继续收到 inset。
 * [content] 给出的话，由它承担底部（导航栏 + 键盘）安全距离；页面自己管底部面板时传 null。
 *
 * 这里必须留在 `:app`：它依赖宿主的 `toolbar_layout` 和 [BaseFragment.applyToolbarInsets]，
 * 而 `:witstudio` 不认识宿主的任何布局。纯视觉零件在 `ceui.pixiv.witstudio.theme.V3Views`。
 */
internal fun Fragment.setupV3Toolbar(
    root: View,
    title: CharSequence,
    content: View? = null,
): Toolbar {
    val toolbar = root.findViewById<Toolbar>(R.id.toolbar)
    root.findViewById<TextView>(R.id.toolbar_title).text = title
    toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    ViewGroupCompat.installCompatInsetsDispatch(root as ViewGroup)
    BaseFragment.applyToolbarInsets(requireActivity(), root)
    WindowInsetsControllerCompat(requireActivity().window, root).isAppearanceLightStatusBars = false
    if (content != null) {
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            ).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            insets
        }
    }
    ViewCompat.requestApplyInsets(root)
    return toolbar
}
