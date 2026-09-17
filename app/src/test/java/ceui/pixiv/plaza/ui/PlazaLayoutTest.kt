package ceui.pixiv.plaza.ui

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.pixiv.plaza.*
import ceui.pixiv.witstudio.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlazaLayoutTest {
    @Test
    fun `toolbar consumes only its own inset while content still receives keyboard and navigation`() {
        val activity = Robolectric.buildActivity(ToolbarTestActivity::class.java).setup()
        try {
            val fragment = ToolbarTestFragment()
            activity.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment).commitNow()
            val root = fragment.requireView()
            fun applyInsets(ime: Int) {
                // Before API 30, the framework carries the IME in the system-window bottom.
                // A fresh legacy WindowInsets also avoids seeding from CONSUMED in Builder20.
                val insets = if (android.os.Build.VERSION.SDK_INT < 30) {
                    WindowInsetsCompat.toWindowInsetsCompat(
                        android.view.WindowInsets::class.java
                            .getConstructor(android.graphics.Rect::class.java)
                            .newInstance(android.graphics.Rect(0, 24, 0, maxOf(16, ime)))
                    )
                } else {
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 16))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                        .build()
                }
                ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
            applyInsets(200)
            assertEquals(24, root.findViewById<View>(R.id.toolbar).paddingTop)
            assertEquals(0, root.findViewById<View>(R.id.toolbar).paddingBottom)
            assertEquals(200, root.findViewById<View>(R.id.plaza_content).paddingBottom)
            applyInsets(0)
            assertEquals(16, root.findViewById<View>(R.id.plaza_content).paddingBottom)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    class ToolbarTestActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            setTheme(R.style.AppTheme)
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
        }
    }

    class ToolbarTestFragment : Fragment(R.layout.fragment_plaza_shell) {
        override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
            setupPlazaToolbar(view, "广场")
        }
    }

    @Test
    fun `plaza uses the shared app toolbar`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val root = LayoutInflater.from(context).inflate(R.layout.fragment_plaza_shell, null)
        val toolbar = root.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        assertNotNull(toolbar)
        assertNotNull(toolbar.navigationIcon)
        assertTrue(toolbar.fitsSystemWindows)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, toolbar.layoutParams.height)
        assertNotNull(toolbar.findViewById<TextView>(R.id.toolbar_title))
    }

    @Test
    fun `recycling a comment as a post restores content alignment`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val post = PlazaPost(1, 42, "Author", "Body", 1, null, null, null, 0, 0, false, emptyList())
        val view = PostView(context) { 42L }
        view.bind(post, false, false, {}, {}, { _, _, _ -> }, comment = true)
        val body =
            (0 until view.childCount).map(view::getChildAt).filterIsInstance<TextView>().first {
                it.text == "Body"
            }
        assertEquals(
            context.dp(44),
            (body.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart,
        )
        view.bind(post, false, false, {}, {}, { _, _, _ -> })
        assertEquals(0, (body.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart)
        assertTrue((body.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin >= 0)
        view.clear()
    }

    @Test
    fun `detail composer keeps text and send reachable on narrow screens with large fonts`() {
        val app = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            val config = android.content.res.Configuration(app.resources.configuration).apply {
                fontScale = scale
                uiMode = if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES
                    else android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme)
            val footer = PlazaReplyBar(context)
            footer.composer.etInput.setText("很长的评论 long reply ".repeat(30))
            footer.composer.replyBar.visibility = View.VISIBLE
            footer.composer.tvReplyBarName.text = "回复 很长的名字".repeat(10)
            footer.measure(
                View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            footer.layout(0, 0, footer.measuredWidth, footer.measuredHeight)
            assertTrue(footer.composer.etInput.width > 0)
            assertTrue(footer.composer.btnSend.right <= footer.width)
            assertEquals(View.GONE, footer.emojiPanel.visibility)
        }
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
            view.bind(post, false, false, {}, {}, { _, _, _ -> })
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
