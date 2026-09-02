package ceui.pixiv.panel

import androidx.fragment.app.Fragment

/**
 * Create a [BottomPanelCoordinator] and attach it to this Fragment's
 * view lifecycle.
 */
fun Fragment.attachBottomPanel(
    host: PanelHost,
    fallbackHeightDp: Int = 270,
    animDurationMs: Long = 250,
): BottomPanelCoordinator =
    BottomPanelCoordinator(host, fallbackHeightDp, animDurationMs)
        .also { it.attach(this) }
