package ceui.pixiv.ui.novel.reader.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import ceui.pixiv.ui.settings.CustomThemeColorSheet
import com.blankj.utilcode.util.Utils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "ru-rRU-w320dp-h640dp",
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTextColorUiTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        MemoryMMKV.values.clear()
        ReflectionHelpers.setField(ReaderSettings.changes, "mPendingData",
            ReflectionHelpers.getStaticField<Any>(LiveData::class.java, "NOT_SET"))
    }

    @Test fun `picker previews text on reader background and only confirmation saves with reset on reopen`() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        host.setup()
        try {
            ReaderSettings.onThemePicked(ReaderTheme.WHITE.id)
            val panel = ReaderSettingsPanel()
            panel.showNow(host.get().supportFragmentManager, ReaderSettingsPanel.TAG)
            fun openPicker(): CustomThemeColorSheet {
                panel.requireView().findViewById<View>(R.id.row_text_color).performClick()
                panel.childFragmentManager.executePendingTransactions()
                return panel.childFragmentManager.fragments.filterIsInstance<CustomThemeColorSheet>().single()
            }
            val cancelled = openPicker()
            cancelled.requireView().findViewById<EditText>(R.id.hex_input).setText("#123456")
            assertNull(ReaderSettings.customTextColor(ReaderTheme.WHITE.id))
            val preview = cancelled.requireView().findViewById<View>(R.id.preview)
            assertEquals(ReaderTheme.WHITE.backgroundColor, (preview.background as GradientDrawable).color!!.defaultColor)
            assertEquals(Color.parseColor("#123456"), cancelled.requireView().findViewById<TextView>(R.id.preview_hex).currentTextColor)
            cancelled.requireView().findViewById<View>(R.id.btn_cancel).performClick()
            panel.childFragmentManager.executePendingTransactions()
            assertNull(ReaderSettings.customTextColor(ReaderTheme.WHITE.id))

            val confirmed = openPicker()
            confirmed.requireView().findViewById<EditText>(R.id.hex_input).setText("#000000")
            confirmed.requireView().findViewById<View>(R.id.btn_confirm).performClick()
            panel.childFragmentManager.executePendingTransactions()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Color.BLACK, ReaderSettings.effectiveTheme().textColor)
            panel.dismissNow()
            val reopened = ReaderSettingsPanel()
            reopened.showNow(host.get().supportFragmentManager, ReaderSettingsPanel.TAG)
            assertEquals("#000000", reopened.requireView().findViewById<TextView>(R.id.text_color_value).text.toString())
            val reset = reopened.requireView().findViewById<View>(R.id.row_reset_text_color)
            assertEquals(View.VISIBLE, reset.visibility)
            reset.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ReaderTheme.WHITE, ReaderSettings.effectiveTheme())
            assertEquals(View.GONE, reset.visibility)
            reopened.dismissNow()

            // Existing app-theme / tag-color callers retain the default result key and filled preview.
            val legacy = CustomThemeColorSheet.newInstance("#123456")
            var result: String? = null
            host.get().supportFragmentManager.setFragmentResultListener(CustomThemeColorSheet.REQUEST_KEY, host.get()) { _, data ->
                result = data.getString(CustomThemeColorSheet.KEY_HEX)
            }
            legacy.showNow(host.get().supportFragmentManager, "legacy-color")
            assertEquals(Color.parseColor("#123456"), (legacy.requireView().findViewById<View>(R.id.preview).background as GradientDrawable).color!!.defaultColor)
            legacy.requireView().findViewById<View>(R.id.btn_confirm).performClick()
            host.get().supportFragmentManager.executePendingTransactions()
            assertEquals("#123456", result)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun `long labels and picker controls fit narrow screens at twice font size in both themes`() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val context = ContextThemeWrapper(app, R.style.AppTheme).apply {
                applyOverrideConfiguration(Configuration(app.resources.configuration).apply {
                    fontScale = 2f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                })
            }
            val root = LayoutInflater.from(context).inflate(R.layout.sheet_custom_theme_color, null)
            root.findViewById<TextView>(R.id.sheet_title).setText(R.string.setting_text_color)
            root.findViewById<TextView>(R.id.preview_hex).text = "${context.getString(R.string.setting_text_color)}\n#123456"
            measure(root, 320, 640)
            val title = root.findViewById<TextView>(R.id.sheet_title)
            val confirm = root.findViewById<TextView>(R.id.btn_confirm)
            assertTrue(title.bottom <= (confirm.parent as View).top)
            for (id in listOf(R.id.sheet_title, R.id.btn_confirm, R.id.btn_cancel, R.id.preview_hex, R.id.hex_input)) {
                assertTextFits(root.findViewById(id))
            }
            val section = LayoutInflater.from(context).inflate(R.layout.section_reader_theme, null)
            section.findViewById<View>(R.id.row_reset_text_color).visibility = View.VISIBLE
            measure(section, 288, null)
            assertTextFits(section.findViewById(R.id.row_reset_text_color))
            val row = section.findViewById<ViewGroup>(R.id.row_text_color)
            for (i in 0 until row.childCount) assertTextFits(row.getChildAt(i) as TextView)
        }
    }

    private fun measure(view: View, widthDp: Int, heightDp: Int?) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(((heightDp ?: 0) * density).toInt(),
                if (heightDp == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertTextFits(text: TextView) {
        assertTrue("clipped: ${text.text}", text.height >= text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
        for (line in 0 until text.layout.lineCount) {
            val available = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
            // getLineMax excludes the trailing whitespace Android leaves at a wrap boundary.
            assertTrue("too wide: ${text.text}, line=$line max=${text.layout.getLineMax(line)} available=$available",
                text.layout.getLineMax(line) <= available + 1f)
        }
    }
}
