package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Themed sheet surface; Material owns dragging, dismissal and nested scrolling. */
public class WitBottomSheet(context: Context) : BottomSheetDialog(context, R.style.ThemeOverlay_Wit_BottomSheet) {
    private val palette = V3Palette.from(context)
    private var padBottomInset = true
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private val surface = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.wit_bg))
            val radius = dp(16).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        clipToOutline = true
        addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.cardHairline)
                cornerRadius = dp(2).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(10)
        })
    }

    /** Scrollable content can own the bottom inset so items draw behind the navigation bar. */
    @JvmOverloads
    public fun setSheetContent(content: View, padBottomInset: Boolean = true): Unit {
        this.padBottomInset = padBottomInset
        while (surface.childCount > 1) surface.removeViewAt(1)
        surface.addView(content, LinearLayout.LayoutParams(-1, -2))
        setContentView(surface)
    }

    override fun onStart(): Unit {
        super.onStart()
        behavior.maxWidth = dp(640)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(Color.TRANSPARENT)
        // A single owner for safe-area padding; the themed surface reaches the screen edge.
        sheet.setPadding(0, 0, 0, 0)
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets -> insets }
        ViewCompat.setOnApplyWindowInsetsListener(surface) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, if (padBottomInset) bars.bottom else 0)
            insets
        }
        window?.let { WindowInsetsControllerCompat(it, surface).isAppearanceLightNavigationBars = !palette.isDark }
        ViewCompat.requestApplyInsets(surface)
    }
}
