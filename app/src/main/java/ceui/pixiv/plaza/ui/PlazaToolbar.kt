package ceui.pixiv.plaza.ui

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

/** Wires the shared app toolbar; content and bottom-panel insets stay with their owners. */
internal fun Fragment.setupPlazaToolbar(
    root: View,
    title: String,
    bottomPanel: Boolean = false,
): Toolbar {
    val toolbar = root.findViewById<Toolbar>(R.id.toolbar)
    root.findViewById<TextView>(R.id.toolbar_title).text = title
    toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    // The shared toolbar consumes its top inset. Keep dispatching to its content sibling on API 29-.
    ViewGroupCompat.installCompatInsetsDispatch(root as ViewGroup)
    BaseFragment.applyToolbarInsets(requireActivity(), root)
    WindowInsetsControllerCompat(requireActivity().window, root).isAppearanceLightStatusBars = false
    if (!bottomPanel) {
        val content = root.findViewById<View>(R.id.plaza_content)
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
