package ceui.pixiv.plaza.ui

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.pixiv.plaza.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlazaLayoutTest {
    @Test
    fun `plaza header follows Figma geometry`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val root = LayoutInflater.from(context).inflate(R.layout.fragment_plaza_shell, null)
        val header = root.findViewById<PlazaHeader>(R.id.plaza_header)
        assertEquals(context.dp(64), header.layoutParams.height)
        assertEquals(context.dp(48), header.back.layoutParams.width)
        assertEquals(
            context.dp(68),
            (header.title.layoutParams as android.widget.FrameLayout.LayoutParams).marginStart,
        )
        assertEquals(context.dp(33), header.action.layoutParams.height)
    }

    @Test
    fun `recycling a comment as a post restores content alignment`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val post = PlazaPost(1, 42, "Author", "Body", 1, null, null, null, 0, 0, false, emptyList())
        val view = PostView(context) { 42L }
        view.bind(post, false, false, {}, {}, { _, _ -> }, comment = true)
        val body =
            (0 until view.childCount).map(view::getChildAt).filterIsInstance<TextView>().first {
                it.text == "Body"
            }
        assertEquals(
            context.dp(44),
            (body.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart,
        )
        view.bind(post, false, false, {}, {}, { _, _ -> })
        assertEquals(0, (body.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart)
        assertTrue((body.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin >= 0)
        view.clear()
    }

    @Test
    fun `detail counters update and footer stays within a narrow large font screen`() {
        val app = RuntimeEnvironment.getApplication()
        val config =
            android.content.res.Configuration(app.resources.configuration).apply { fontScale = 2f }
        val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
        val footer = PlazaReplyBar(context, {}, {}, {})
        footer.bind(
            PlazaPost(1, 42, "Author", "Body", 1, null, null, null, 9999, 8888, false, emptyList())
        )
        footer.measure(
            View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        footer.layout(0, 0, footer.measuredWidth, footer.measuredHeight)
        assertTrue(footer.getChildAt(0).width > 0)
        assertTrue(footer.getChildAt(2).right <= footer.width - footer.paddingRight)
        footer.bind(null)
        assertFalse(footer.getChildAt(0).isEnabled)
        assertFalse(footer.getChildAt(1).isEnabled)
    }

    @Test
    fun `nine image grid measures in narrow wide dark and large font configurations`() {
        for (dark in listOf(false, true)) for (widthDp in listOf(320, 720)) {
            val app = RuntimeEnvironment.getApplication()
            val config =
                android.content.res.Configuration(app.resources.configuration).apply {
                    uiMode =
                        if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                        else android.content.res.Configuration.UI_MODE_NIGHT_NO
                    fontScale = 2f
                }
            val context =
                ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
            val view = PostView(context) { 42L }
            val post =
                PlazaPost(
                    1,
                    42,
                    "Long author name 很长的用户名",
                    "A long post 正文内容".repeat(30),
                    1,
                    123,
                    "manga",
                    null,
                    8,
                    9,
                    false,
                    (1..9).map {
                        PlazaImage(
                            "id-$it",
                            960,
                            1200,
                            "image/jpeg",
                            "https://example.invalid/$it",
                            999999,
                        )
                    },
                )
            view.bind(post, false, false, {}, {}, { _, _ -> })
            val width = context.dp(widthDp)
            fun layout() {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                view.layout(0, 0, width, view.measuredHeight)
            }
            layout()
            val grid =
                (0 until view.childCount)
                    .map(view::getChildAt)
                    .filterIsInstance<PlazaIllustGrid>()
                    .single()
            grid.onMeasured?.invoke(grid.width)
            layout()
            assertEquals(3, grid.childCount)
            for (r in 0 until grid.childCount) {
                val row = grid.getChildAt(r) as android.view.ViewGroup
                assertEquals(3, row.childCount)
                for (c in 0 until row.childCount) {
                    val tile = row.getChildAt(c)
                    assertTrue(tile.measuredWidth > 0)
                    assertTrue(tile.measuredHeight > 0)
                    assertTrue(tile.right <= width)
                }
            }
            view.clear()
        }
    }
}
