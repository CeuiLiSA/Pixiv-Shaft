package ceui.pixiv.ui.novel.reader.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.children
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import ceui.pixiv.ui.novel.reader.tts.NovelTtsController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "ru-rRU-w320dp-h640dp",
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTtsSettingsTest {
    @Before fun setUp() {
        // Use the same MMKV shadow as the other ReaderSettings tests: its
        // cached store can survive Robolectric's application class-loader reuse.
        MemoryMMKV.values.clear()
        NovelTtsController.playbackState = NovelTtsController.PlaybackState()
    }

    @Test fun `settings retain independent switches and speed after reopening with large text`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        var dialog: Dialog? = null
        try {
            for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(host.get().resources.configuration).apply {
                    fontScale = 2f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
                val themed = ContextThemeWrapper(host.get(), R.style.AppTheme).apply {
                    applyOverrideConfiguration(config)
                }
                showReaderTtsSettings(themed)
                dialog = requireNotNull(ShadowDialog.getLatestDialog())
                shadowOf(Looper.getMainLooper()).idle()
                val root = dialog.window!!.decorView
                val switches = descendants(root).filterIsInstance<SwitchCompat>().toList()
                assertEquals(4, switches.size)
                val before = listOf(ReaderSettings.ttsHighlight, ReaderSettings.ttsAutoPage,
                    ReaderSettings.ttsDoubleTap, ReaderSettings.ttsShowPageAction)
                assertEquals(before, switches.map { it.isChecked })
                for (toggle in switches.take(3)) (toggle.parent as View).performClick()
                val expected = before.mapIndexed { index, value -> if (index < 3) !value else value }
                assertEquals(expected, listOf(ReaderSettings.ttsHighlight, ReaderSettings.ttsAutoPage,
                    ReaderSettings.ttsDoubleTap, ReaderSettings.ttsShowPageAction))

                val speed = descendants(root).filterIsInstance<TextView>().single {
                    it.isClickable && it.text.startsWith(themed.getString(R.string.reader_menu_tts_speed))
                }
                speed.performClick()
                val choices = requireNotNull(ShadowDialog.getLatestDialog())
                val rate = descendants(choices.window!!.decorView).filterIsInstance<TextView>().single {
                    it.text.toString() == themed.getString(R.string.reader_tts_speed_value, 1.5f)
                }
                generateSequence(rate as View) { it.parent as? View }.first { it.isClickable }.performClick()
                assertEquals(1.5f, ReaderSettings.ttsSpeed, 0f)
                dialog.dismiss()
                showReaderTtsSettings(themed)
                dialog = requireNotNull(ShadowDialog.getLatestDialog())
                shadowOf(Looper.getMainLooper()).idle()
                val reopened = descendants(dialog.window!!.decorView).filterIsInstance<SwitchCompat>().toList()
                assertEquals(expected, reopened.map { it.isChecked })
                assertTrue(reopened.none { it.isSaveEnabled })
                // Native layout must allow the long Russian labels to wrap at 200%.
                for (toggle in reopened) {
                    val row = toggle.parent as ViewGroup
                    val width = (260 * themed.resources.displayMetrics.density).toInt()
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                    val label = row.findViewById<TextView>(R.id.label_text)
                    assertTrue(label.layout.lineCount > 1)
                    assertTrue(label.height >= label.layout.height + label.compoundPaddingTop + label.compoundPaddingBottom)
                }
                dialog.dismiss()
            }
        } finally {
            dialog?.dismiss()
            host.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (child in view.children) yieldAll(descendants(child))
    }

}
