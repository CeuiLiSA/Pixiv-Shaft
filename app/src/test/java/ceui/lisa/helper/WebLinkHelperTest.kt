package ceui.lisa.helper

import android.app.Application
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.ContextThemeWrapper
import android.widget.TextView
import ceui.lisa.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class WebLinkHelperTest {

    @Test
    fun labelledHtmlLinkKeepsItsDestination() {
        val text = formatWebLinks("<a href=\"https://example.com/profile\">主页</a>")

        assertEquals("主页", text.toString())
        assertEquals(listOf("https://example.com/profile"), text.urls())
        assertTrue(text.getSpans(0, text.length, URLSpan::class.java).all { it is CustomTabUrlSpan })
    }

    @Test
    fun urlLookingAnchorLabelDoesNotOverrideItsDestination() {
        val text = formatWebLinks(
            "<a href=\"https://example.com/target\">https://example.org/label</a>"
        )

        assertEquals(listOf("https://example.com/target"), text.urls())
    }

    @Test
    fun bareUrlsAreAddedAlongsideHtmlLinksWithoutOverlappingThem() {
        val text = formatWebLinks(
            "<b><a href=\"https://example.com/profile\">主页</a></b> https://example.org/other"
        )

        assertEquals(
            listOf("https://example.com/profile", "https://example.org/other"),
            text.urls().sorted(),
        )
        assertTrue(text.getSpans(0, text.length, URLSpan::class.java).all { it is CustomTabUrlSpan })
    }

    @Test
    fun customLinkBindingDisablesTextViewAutoLinkBeforeSettingText() {
        val view = TextView(RuntimeEnvironment.getApplication()).apply {
            autoLinkMask = Linkify.WEB_URLS
        }

        view.setWebLinks("<a href=\"https://example.com/target\">https://example.org/label</a>")

        assertEquals(0, view.autoLinkMask)
        val text = view.text as Spanned
        assertEquals(listOf("https://example.com/target"), text.urls())
        assertTrue(text.getSpans(0, text.length, URLSpan::class.java).single() is CustomTabUrlSpan)
    }

    @Test
    fun workspaceFieldsKeepAutomaticLinksFromTheirSharedStyle() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = TextView(context, null, 0, R.style.AboutUserRight)

        view.text = "https://example.com/tool"

        assertEquals(Linkify.ALL, view.autoLinkMask)
        assertEquals(listOf("https://example.com/tool"), (view.text as Spanned).urls())
    }

    @Test
    fun emptyInputAndPlainTextLineBreaksAreSupported() {
        assertEquals("", formatWebLinks(null).toString())
        assertEquals("first\nsecond", formatWebLinks("first\r\nsecond").toString())
        assertEquals(listOf("https://example.com"), formatWebLinks("https://example.com").urls())
    }

    private fun Spanned.urls(): List<String> =
        getSpans(0, length, URLSpan::class.java).map { it.url }
}
