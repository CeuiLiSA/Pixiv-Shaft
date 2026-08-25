package ceui.pixiv.chat.base

import android.graphics.Color
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import com.blankj.utilcode.util.BarUtils

/**
 * Wires the chat toolbar (`chat_layout_toolbar.xml` — same shape as the app-wide `toolbar_layout.xml`).
 *
 * Three things this helper owns at runtime so the static XML doesn't have to:
 *
 *  1. **Brand background color.** Setting `android:background="?attr/colorPrimary"`
 *     in the XML doesn't reliably resolve to the user's brand color, because
 *     the chat fragment applies `Theme.Material3.DayNight.NoActionBar` as a
 *     **full theme** (not a `ThemeOverlay`) on the root — which shadows
 *     colorPrimary with the M3 baseline tone. Pull the actual brand
 *     `#RRGGBB` from `Shaft.getThemeColor()` (the per-`themeIndex` lookup
 *     used everywhere else in the app) and paint it directly.
 *
 *  2. **Status-bar inset as top padding.** `fitsSystemWindows="true"`
 *     looks right but on edge-to-edge activities (BaseActivity calls
 *     EdgeToEdge.enable) the default Toolbar inset handler inhales *all*
 *     dispatched insets including the bottom navigation/gesture inset,
 *     leaving a ~50dp empty band below the title. Apply only the status
 *     bar height as top padding — that's all a top-anchored toolbar needs.
 *
 *  3. **Title + back nav.** Same as before — write the title TextView,
 *     hook the toolbar's navigationIcon click into
 *     [androidx.activity.OnBackPressedDispatcher] when `showBack`.
 */
fun Fragment.setupToolbar(title: String, showBack: Boolean = false) {
    val v = view ?: return
    v.findViewById<TextView>(R.id.toolbar_title)?.text = title

    val toolbar = v.findViewById<Toolbar>(R.id.toolbar) ?: return
    toolbar.setBackgroundColor(Color.parseColor(Shaft.getThemeColor()))
    toolbar.updatePadding(top = BarUtils.getStatusBarHeight())

    if (showBack) {
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    } else {
        toolbar.navigationIcon = null
    }
}
