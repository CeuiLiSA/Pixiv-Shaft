package ceui.pixiv.ui.discovery

import android.app.Application
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.dp
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDiscoveryLayoutTest {
    @Test fun `age controls remain readable across themes widths and large translated text`() {
        val app = RuntimeEnvironment.getApplication()
        for (locale in listOf("zh-CN", "en", "ja", "ko", "ru", "tr", "zh-TW")) {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                val config = Configuration(app.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(locale))
                    fontScale = scale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme_Index0)
                val root = LayoutInflater.from(context).inflate(R.layout.fragment_web_discovery, null)
                val tabs = root.findViewById<TabLayout>(R.id.discovery_modes)
                listOf(R.string.string_390, R.string.string_440, R.string.string_441).forEach {
                    tabs.addTab(tabs.newTab().setText(it))
                }
                for (width in listOf(320, 800)) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(context.dp(width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(context.dp(900), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val scenario = "$locale dark=$dark scale=$scale width=$width"
                    assertTrue(scenario, tabs.height >= context.dp(48))
                    val labels = tabs.descendants().filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
                    assertEquals(scenario, 3, labels.size)
                    for (label in labels) {
                        val layout = requireNotNull(label.layout)
                        assertTrue("$scenario ${label.text}", layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                        for (line in 0 until layout.lineCount) {
                            assertEquals("$scenario ${label.text}", 0, layout.getEllipsisCount(line))
                        }
                    }
                }
            }
        }
    }

    private fun View.descendants(): List<View> = listOf(this) + if (this is ViewGroup) {
        (0 until childCount).flatMap { getChildAt(it).descendants() }
    } else emptyList()
}
