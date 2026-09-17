package ceui.pixiv.plaza.ui

import android.app.Application
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.navigation.TemplateRouteFactory
import ceui.pixiv.witstudio.theme.*
import org.robolectric.Shadows.shadowOf
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
class PlazaReportFragmentTest {
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    @Test fun `report form opens with no reason selected and restores its draft after recreation`() = verifyForm(1f)

    @Test @Config(qualifiers = "w320dp-h640dp-night")
    fun `narrow dark report form supports large fonts and preserves the draft`() = verifyForm(2f)


    @Test @Config(qualifiers = "w840dp-h1000dp")
    fun `wide report page keeps the same editable form`() = verifyForm(1f)

    @Test fun `report entry navigates to a page with its target instead of opening a dialog`() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try {
            val activity=controller.get()
            activity.showPlazaModeration(123L,99L,"user")
            val intent=shadowOf(activity).nextStartedActivity
            assertEquals(TemplateRoute.PLAZA_REPORT.key,intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            val fragment=TemplateRouteFactory.create(TemplateRoute.PLAZA_REPORT,intent)
            assertTrue(fragment is PlazaReportFragment)
            assertEquals(123L,fragment.arguments!!.getLong("postId"))
            assertEquals(99L,fragment.arguments!!.getLong("targetUid"))
            assertEquals("user",fragment.arguments!!.getString("mode"))
            assertTrue(activity.supportFragmentManager.fragments.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private fun verifyForm(fontScale: Float) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val resources = controller.get().resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(android.content.res.Configuration(resources.configuration).apply { this.fontScale = fontScale }, resources.displayMetrics)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup().visible()
        try {
            val activity=controller.get()
            val fragment=PlazaReportFragment().apply { arguments=bundleOf("mode" to "post", "postId" to 1L, "targetUid" to 99L) }
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content,fragment,"report").commitNow()
            val views=children(fragment.requireView())
            assertEquals(10,views.filterIsInstance<RadioButton>().size)
            assertTrue(views.any { it is androidx.core.widget.NestedScrollView })
            assertTrue(views.filterIsInstance<RadioButton>().all { it.minHeight >= activity.dp(48) })
            assertTrue(views.filterIsInstance<RadioButton>().none { it.isChecked })
            val input=fragment.requireView().findViewById<EditText>(R.id.plaza_report_details_input)
            // 卡片底挪到外层容器上，输入框自己透明：两层圆角叠在一起会在四角描出一圈深边。
            assertNotNull(fragment.requireView().findViewById<View>(R.id.plaza_report_details_card).background)
            assertTrue(input.minimumHeight >= activity.dp(168))
            // 证据区没有单独的添加胶囊，下一个空格子本身就是添加槽。
            val addSlot=fragment.requireView().findViewById<View>(R.id.plaza_report_add_photos)
            assertTrue(addSlot.isClickable)
            assertEquals(activity.getString(R.string.plaza_add_photos,3),addSlot.contentDescription)
            val submit=fragment.requireView().findViewById<TextView>(R.id.plaza_report_submit_button)
            assertFalse(submit.isEnabled)
            views.filterIsInstance<RadioButton>().last().performClick()
            assertEquals(1,views.filterIsInstance<RadioButton>().count { it.isChecked })
            assertTrue("Other reason permits an empty description",submit.isEnabled)
            views.filterIsInstance<EditText>().single().setText("举报说明保留")
            controller.recreate()
            val restored=controller.get().supportFragmentManager.findFragmentByTag("report") as PlazaReportFragment
            val restoredViews=children(restored.requireView())
            assertTrue(restoredViews.filterIsInstance<RadioButton>().last().isChecked)
            assertEquals("举报说明保留",restoredViews.filterIsInstance<EditText>().single().text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
