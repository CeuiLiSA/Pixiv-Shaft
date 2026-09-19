package ceui.pixiv.widgets

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import com.blankj.utilcode.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class TagChipClickTest {
    private lateinit var flow: V3TagFlowView
    private val actions = mutableListOf<String>()

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(host, R.style.AppTheme)
        flow = V3TagFlowView(context).apply {
            showRemoveIcon = true
            setTagNames(listOf("初音ミク"))
            onTagClick = { actions += "remove" }
            onTagBodyClick = { actions += "edit" }
            onTagLongClick = { actions += "menu" }
        }
        host.setContentView(flow)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `body and close have distinct targets in both layout directions`() {
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val chip = chip(direction)
            actions.clear()
            val close = if (direction == View.LAYOUT_DIRECTION_RTL) 1f else chip.width - 1f
            val body = if (direction == View.LAYOUT_DIRECTION_RTL) {
                chip.width - chip.paddingStart - 1f
            } else chip.paddingStart + 1f
            tap(chip, body)
            tap(chip, close)
            assertEquals("direction=$direction, width=${chip.width}", listOf("edit", "remove"), actions)
        }
    }

    @Test
    fun `long press does not leave touch coordinates for a later keyboard click`() {
        val chip = chip()
        down(chip, chip.paddingStart + 1f)
        chip.performLongClick()
        chip.performClick()
        assertEquals(listOf("menu", "remove"), actions)
    }

    @Test
    fun `cancelled touches do not change the existing non-touch action`() {
        val chip = chip()
        down(chip, chip.width / 2f)
        event(chip, MotionEvent.ACTION_CANCEL, chip.width / 2f)
        chip.performClick()
        assertEquals(listOf("remove"), actions)
    }

    @Test
    fun `noneditable tags retain their host callback`() {
        flow.showRemoveIcon = false
        tap(chip(), 20f)
        assertEquals(listOf("remove"), actions)
    }

    private fun chip(direction: Int = View.LAYOUT_DIRECTION_LTR): TextView {
        val chip = flow.getChildAt(0) as TextView
        chip.layoutDirection = direction
        chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        chip.layout(0, 0, chip.measuredWidth, chip.measuredHeight)
        return chip
    }

    private fun down(chip: TextView, x: Float) = event(chip, MotionEvent.ACTION_DOWN, x)

    private fun tap(chip: TextView, x: Float) {
        down(chip, x)
        event(chip, MotionEvent.ACTION_UP, x)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun event(chip: TextView, action: Int, x: Float) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, x, chip.height / 2f, 0).also {
            chip.dispatchTouchEvent(it)
            it.recycle()
        }
    }
}
