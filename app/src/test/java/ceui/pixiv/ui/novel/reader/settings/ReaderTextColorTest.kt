package ceui.pixiv.ui.novel.reader.settings

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.core.view.children
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.Paginator
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.NovelScrollReaderView
import ceui.pixiv.ui.novel.reader.render.PageView
import ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class,
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTextColorTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    @Before fun setUp() {
        MemoryMMKV.values.clear()
        // ReaderSettings survives Robolectric sandbox reuse; do not inherit another test's pending post.
        ReflectionHelpers.setField(ReaderSettings.changes, "mPendingData",
            ReflectionHelpers.getStaticField<Any>(LiveData::class.java, "NOT_SET"))
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
        RuntimeEnvironment.setQualifiers("notnight")
    }

    @Test fun `presets keep their defaults and each custom color survives switching until reset`() {
        for (preset in ReaderTheme.PRESETS) {
            ReaderSettings.onThemePicked(preset.id)
            assertEquals(preset, ReaderSettings.effectiveTheme())
        }
        ReaderSettings.setTextColor(ReaderTheme.WHITE.id, Color.BLACK)
        ReaderSettings.setTextColor(ReaderTheme.NIGHT.id, Color.WHITE)
        repeat(2) {
            ReaderSettings.onThemePicked(ReaderTheme.WHITE.id)
            assertEquals(Color.BLACK, ReaderSettings.effectiveTheme().textColor)
            ReaderSettings.onThemePicked(ReaderTheme.NIGHT.id)
            assertEquals(Color.WHITE, ReaderSettings.effectiveTheme().textColor)
        }
        ReaderSettings.setTextColor(ReaderTheme.NIGHT.id, null)
        assertNull(ReaderSettings.customTextColor(ReaderTheme.NIGHT.id))
        assertEquals(ReaderTheme.NIGHT, ReaderSettings.effectiveTheme())
        assertEquals(Color.BLACK, ReaderSettings.customTextColor(ReaderTheme.WHITE.id))
    }

    @Test fun `following system night uses independent colors and picker result stays with original theme`() {
        ReaderSettings.onThemePicked(ReaderTheme.WHITE.id)
        ReaderSettings.followSystemDarkMode = true
        val openedFor = ReaderSettings.effectiveTheme().id
        ReaderSettings.setTextColor(openedFor, Color.BLACK)
        RuntimeEnvironment.setQualifiers("night")
        assertEquals(ReaderTheme.NIGHT, ReaderSettings.effectiveTheme())
        // A color confirmed after a system theme change still belongs to the background previewed.
        ReaderSettings.setTextColor(openedFor, 0x00123456)
        assertEquals(ReaderTheme.NIGHT, ReaderSettings.effectiveTheme())
        RuntimeEnvironment.setQualifiers("notnight")
        assertEquals(0xFF123456.toInt(), ReaderSettings.effectiveTheme().textColor)
        assertTrue(ReaderSettings.followSystemDarkMode)
    }

    @Test fun `custom color reaches paged and scrolling text and chapter paint`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val text = "文字颜色 Text color"
        val tokens = listOf(ContentToken.Paragraph(0, text.length, text))
        val geometry = PageGeometry(320, 480, 16f, 16f, 16f, 16f)
        for (preset in listOf(ReaderTheme.WHITE, ReaderTheme.NIGHT)) {
            val color = if (preset.isDark) Color.WHITE else Color.BLACK
            ReaderSettings.onThemePicked(preset.id)
            ReaderSettings.setTextColor(preset.id, color)
            val theme = ReaderSettings.effectiveTheme()
            val style = TypeStyle.from(context, ReaderSettings.snapshot(), theme)
            assertEquals(color, style.chapterPaint.color)
            assertEquals(preset.backgroundColor, style.backgroundColor)
            assertEquals(preset.linkColor, style.linkColor)
            val pages = Paginator(tokens, geometry, style, TextMeasurer(context)).paginate()
            val paged = PageView(context).apply {
                bind(pages.first(), style, geometry)
                measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                layout(0, 0, 320, 480)
            }
            val blocks = paged.children.filterIsInstance<ReaderTextBlockView>().toList()
            assertTrue(blocks.isNotEmpty())
            assertTrue(blocks.all { it.currentTextColor == color })
            val scroll = NovelScrollReaderView(context)
            scroll.bind(tokens, style, geometry) { null }
            val adapter = requireNotNull(scroll.adapter)
            val holder = adapter.createViewHolder(scroll, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)
            assertEquals(color, (holder.itemView as TextView).currentTextColor)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ReaderSettings.ChangeEvent.Theme, ReaderSettings.changes.value)
    }
}
