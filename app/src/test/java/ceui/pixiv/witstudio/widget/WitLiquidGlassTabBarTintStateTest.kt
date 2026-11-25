package ceui.pixiv.witstudio.widget

import android.os.SystemClock
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WitLiquidGlassTabBarTintStateTest {

    @Test
    fun `masking tint stays stateful so tab icon drawable states stay synced across switches`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val glassBar = WitLiquidGlassView(context)
        val navView = BottomNavigationView(context)
        glassBar.addView(
            navView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val menu = navView.menu
        menu.add(Menu.NONE, R.id.action_1, Menu.NONE, "推荐").setIcon(R.drawable.ic_tuijian)
        menu.add(Menu.NONE, R.id.action_2, Menu.NONE, "发现").setIcon(R.drawable.ic_discover)
        menu.add(Menu.NONE, R.id.action_3, Menu.NONE, "动态").setIcon(R.drawable.ic_dongtai)

        val tabBar = WitLiquidGlassTabBar.attach(glassBar, navView)
        tabBar.applyThemeColors(
            selectedColor = 0xFF686BDD.toInt(),
            unselectedColor = 0xA81A1A2E.toInt(),
            restingPillColor = 0x181A1A2E,
            isDark = false,
        )
        tabBar.isEnabled = true

        glassBar.measure(
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
        )
        glassBar.layout(0, 0, 900, 180)
        navView.selectedItemId = R.id.action_1
        tabBar.syncSelection(animated = false)

        // Start a touch gesture on tab 3 so maskingMode activates before selectedItemId changes.
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 750f, 90f, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, 750f, 90f, 0)
        try {
            glassBar.dispatchTouchEvent(down)
            assertTrue(
                "Masking icon tint must remain stateful so ImageView propagates checked/selected changes",
                navView.itemIconTintList?.isStateful == true,
            )
            glassBar.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        // Settle back to resting tint list (simulates end of spring animation).
        tabBar.syncSelection(animated = false)
        assertTrue(navView.itemIconTintList?.isStateful == true)

        val menuView = navView.getChildAt(0) as ViewGroup
        val firstIconDrawable = findFirstImageView(menuView.getChildAt(0))!!.drawable
        val thirdIconDrawable = findFirstImageView(menuView.getChildAt(2))!!.drawable

        val firstCheckedOrSelected = firstIconDrawable.state.any {
            it == android.R.attr.state_checked || it == android.R.attr.state_selected
        }
        val thirdCheckedOrSelected = thirdIconDrawable.state.any {
            it == android.R.attr.state_checked || it == android.R.attr.state_selected
        }
        assertFalse("Unselected first tab icon must not retain stale checked/selected drawable state", firstCheckedOrSelected)
        assertTrue("Selected third tab icon must have checked/selected drawable state", thirdCheckedOrSelected)
    }

    private fun findFirstImageView(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstImageView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
