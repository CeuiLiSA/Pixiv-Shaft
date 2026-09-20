package ceui.pixiv.ui.discovery

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import ceui.lisa.R
import ceui.lisa.utils.Settings
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverSocialSectionTest {
    private fun context(locale: String = "zh-CN", fontScale: Float = 1f, dark: Boolean = false,
                        theme: Int = R.style.AppTheme_Index0): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), theme)
    }

    private fun measure(view: View, widthDp: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(view.context.dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun View.descendants(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()

    @Test fun `translated text and artwork do not overlap or clip on narrow and large-font screens`() {
        for (locale in listOf("zh-CN", "zh-TW", "en", "ja", "ko", "ru", "tr")) {
            for (width in listOf(320, 390, 800)) for (scale in listOf(1f, 2f)) {
                val ctx = context(locale, scale)
                val section = DiscoverSocialSection(ctx)
                measure(section, width)
                val scenario = "$locale $width dp / font $scale"
                for (card in section.descendants().filterIsInstance<SocialEntryCard>()) {
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        card.offsetDescendantRectToMyCoords(view, it)
                    }
                    val art = card.descendants().filterIsInstance<DiscoverSocialArtView>().single()
                    assertTrue(scenario, Rect(0, 0, card.width, card.height).contains(bounds(art)))
                    for (text in card.descendants().filterIsInstance<TextView>()) {
                        assertTrue("$scenario ${text.text}", Rect(0, 0, card.width, card.height).contains(bounds(text)))
                        assertFalse("$scenario ${text.text}", Rect.intersects(bounds(art), bounds(text)))
                        val layout = requireNotNull(text.layout)
                        assertTrue("$scenario ${text.text}", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                        for (line in 0 until layout.lineCount) {
                            assertEquals(scenario, 0, layout.getEllipsisCount(line))
                            // LineWidth includes trailing spaces moved across a soft line break.
                            assertTrue("$scenario ${text.text}", layout.getLineMax(line) <= layout.width + 1f)
                        }
                    }
                }
            }
        }
    }

    @Test fun `both entries are visible by default and resize as one accessible action each`() {
        val section = DiscoverSocialSection(context())
        var chatClicks = 0
        var communityClicks = 0
        section.setEntryClickListeners({ chatClicks++ }, { communityClicks++ })
        measure(section, 390)
        val chat = section.findViewById<View>(R.id.discover_chat_entry)
        val community = section.findViewById<View>(R.id.discover_community_entry)
        assertEquals(chat.height, community.height)
        chat.performClick(); community.performClick()
        assertEquals(1, chatClicks); assertEquals(1, communityClicks)
        for (card in listOf(chat, community)) {
            assertEquals(Button::class.java.name, card.accessibilityClassName)
            assertTrue(card.width >= card.context.dp(48) && card.height >= card.context.dp(48))
            assertEquals(1, card.descendants().count { it.isClickable })
        }
        val halfWidth = chat.width
        measure(section, 320)
        assertTrue(chat.width > halfWidth)
        assertEquals(View.VISIBLE, chat.visibility)
        assertEquals(View.VISIBLE, community.visibility)
        assertTrue(community.top >= chat.bottom)
        measure(section, 390)
        assertEquals(halfWidth, chat.width)
    }

    @Test fun `legacy entry switches are ignored and no longer exported with settings`() {
        Utils.init(RuntimeEnvironment.getApplication())
        val gson = Gson()
        val settings = gson.fromJson("""{"showChatRoomEntry":false,"showPlazaEntry":false,"showChatRoomPushBanner":true}""", Settings::class.java)
        val exported = gson.toJsonTree(settings).asJsonObject
        assertFalse(exported.has("showChatRoomEntry"))
        assertFalse(exported.has("showPlazaEntry"))
        assertTrue(settings.isShowChatRoomPushBanner)
        assertFalse(Settings().isShowChatRoomPushBanner)
    }

    @Test fun `native theme presets produce readable text on both actual card fills`() {
        val themes = listOf(R.style.AppTheme_Index0, R.style.AppTheme_Index1, R.style.AppTheme_Index2,
            R.style.AppTheme_Index3, R.style.AppTheme_Index4, R.style.AppTheme_Index5, R.style.AppTheme_Index6,
            R.style.AppTheme_Index7, R.style.AppTheme_Index8, R.style.AppTheme_Index9)
        for (dark in listOf(false, true)) for (theme in themes) {
            val ctx = context(dark = dark, theme = theme)
            val colors = DiscoverSocialColors(ctx)
            val native = V3Palette.from(ctx)
            assertEquals(native.primary, colors.palette.primary)
            for (fill in listOf(native.cardFill, colors.chatFill)) {
                assertTrue(ColorUtils.calculateContrast(colors.accentOn(fill), fill) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(colors.secondaryOn(fill), fill) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(colors.ink, fill) >= 4.5)
            }
            assertTrue(ColorUtils.calculateContrast(colors.onPrimary, native.primary) >= 4.5)
            assertEquals(Color.alpha(colors.chatFill), 255)
        }
    }
}
