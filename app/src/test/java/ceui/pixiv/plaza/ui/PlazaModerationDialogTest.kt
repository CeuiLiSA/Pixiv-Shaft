package ceui.pixiv.plaza.ui

import android.app.Application
import android.widget.EditText
import android.widget.RadioButton
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.core.os.bundleOf
import ceui.lisa.R
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlazaModerationDialogTest {
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    @Test fun `report form opens with no reason selected and restores its draft after recreation`() = verifyForm(1f)

    @Test @Config(qualifiers = "w320dp-h640dp-night")
    fun `narrow dark report form supports large fonts and preserves the draft`() = verifyForm(2f)

    private fun verifyForm(fontScale: Float) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val resources = controller.get().resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(android.content.res.Configuration(resources.configuration).apply { this.fontScale = fontScale }, resources.displayMetrics)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup().visible()
        try {
            val activity=controller.get()
            val fragment=PlazaModerationDialog().apply { arguments=bundleOf("mode" to "post", "postId" to 1L, "targetUid" to 99L) }
            fragment.showNow(activity.supportFragmentManager,"report")
            val views=children(fragment.requireDialog().window!!.decorView)
            assertEquals(9,views.filterIsInstance<RadioButton>().size)
            assertTrue(views.any { it is android.widget.ScrollView })
            assertTrue(views.filterIsInstance<RadioButton>().all { it.minHeight >= activity.dp(48) })
            assertTrue(views.filterIsInstance<RadioButton>().none { it.isChecked })
            views.filterIsInstance<RadioButton>().last().performClick()
            views.filterIsInstance<EditText>().single().setText("举报说明保留")
            controller.recreate()
            val restored=controller.get().supportFragmentManager.findFragmentByTag("report") as PlazaModerationDialog
            val restoredViews=children(restored.requireDialog().window!!.decorView)
            assertTrue(restoredViews.filterIsInstance<RadioButton>().last().isChecked)
            assertEquals("举报说明保留",restoredViews.filterIsInstance<EditText>().single().text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
