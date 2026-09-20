package ceui.pixiv.plaza.ui

import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.ui.v3.setupV3Toolbar

/** Wires the shared app toolbar; content and bottom-panel insets stay with their owners. */
internal fun Fragment.setupPlazaToolbar(
    root: View,
    title: String,
    bottomPanel: Boolean = false,
): Toolbar = setupV3Toolbar(
    root,
    title,
    content = if (bottomPanel) null else root.findViewById(R.id.plaza_content),
)
