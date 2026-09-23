package ceui.pixiv.ui.novel.reader.render

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.children
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.Paginator
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTtsRenderingTest {
    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun settings(size: Int = 18, spacing: Float = 1.6f, gap: Float = 1f, font: String = "system") =
        ReaderSettings.Snapshot(
            fontSizeSp = size, lineSpacing = spacing, paragraphSpacingLines = gap,
            horizontalMarginDp = 0, verticalMarginDp = 0, firstLineIndent = 0,
            letterSpacing = 0f, boldText = false, fontId = font, fontWeight = 400,
            themeId = "preset_white", customThemeId = 0, followSystemDarkMode = false,
            lightThemeMemoryId = "preset_white", backgroundImagePath = null,
            flipMode = FlipMode.None, imagePlacement = ImagePlacement.Center,
            imageScaleMode = ImageScaleMode.Fit,
        )


    private fun layout(view: View, width: Int = 320, height: Int = 480) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun `paged highlights clip to the visible slice in both themes and large fonts`() {
        val text = "正在朗读 A long paragraph with more words. ".repeat(20)
        val tokens = listOf(ContentToken.Paragraph(0, text.length + 2, text, 2))
        for (theme in listOf(ReaderTheme.WHITE, ReaderTheme.NIGHT)) {
            for (fontScale in listOf(1f, 2f)) {
                val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
                val ctx = ContextThemeWrapper(context.createConfigurationContext(config), R.style.AppTheme)
                val style = TypeStyle.from(ctx, settings(), theme)
                val geo = PageGeometry(320, 480, 16f, 16f, 16f, 16f)
                val pages = Paginator(tokens, geo, style, TextMeasurer(ctx)).paginate()
                assertTrue(pages.size > 1)
                val range = (pages[0].charEnd - 2)..(pages[1].charStart + 4)
                for (page in pages.take(2)) {
                    val view = PageView(ctx)
                    view.bind(page, style, geo, overlays = PageOverlays(ttsActiveRange = range))
                    layout(view)
                    val blocks = view.children.filterIsInstance<ReaderTextBlockView>().toList()
                    assertTrue(blocks.isNotEmpty())
                    assertTrue(blocks.any { block ->
                        val content = block.text as Spanned
                        content.getSpans(0, content.length, BackgroundColorSpan::class.java).any {
                            it.backgroundColor == style.highlightColor && content.getSpanEnd(it) > content.getSpanStart(it)
                        }
                    })
                    view.updateOverlays(PageOverlays.EMPTY)
                    assertTrue(blocks.all { (it.text as Spanned).getSpans(0, it.text.length, BackgroundColorSpan::class.java).isEmpty() })
                }
            }
        }
    }

    @Test fun `scroll highlights use displayed text start and survive recycling without losing search`() {
        val style = TypeStyle.from(context, settings(), ReaderTheme.NIGHT)
        val tokens = listOf(ContentToken.Paragraph(100, 110, "abcdef", 104), ContentToken.Paragraph(120, 126, "uvwxyz"))
        val view = NovelScrollReaderView(context)
        view.bind(tokens, style, PageGeometry(320, 480, 16f, 16f, 16f, 16f)) { null }
        view.applySearchHighlights(listOf(HighlightRange(104, 105, 0x6600FF00)))
        view.setTtsRange(106..108)
        val adapter = requireNotNull(view.adapter)
        val holder = adapter.createViewHolder(view, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val content = (holder.itemView as TextView).text as Spanned
        val spans = content.getSpans(0, content.length, BackgroundColorSpan::class.java)
        assertEquals(2, spans.size)
        val tts = spans.single { it.backgroundColor == style.highlightColor }
        assertEquals(2, content.getSpanStart(tts))
        assertEquals(5, content.getSpanEnd(tts))
        // A cached view may come back without onBind after speech moved away.
        view.setTtsRange(null)
        adapter.onViewAttachedToWindow(holder)
        val remaining = content.getSpans(0, content.length, BackgroundColorSpan::class.java)
        assertEquals(1, remaining.size)
        assertEquals(0x6600FF00, remaining.single().backgroundColor)
        adapter.bindViewHolder(holder, 1)
        val rebound = (holder.itemView as TextView).text as Spanned
        assertTrue(rebound.getSpans(0, rebound.length, BackgroundColorSpan::class.java).isEmpty())
    }

    @Test fun `following a long scroll paragraph makes the spoken line visible`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val ctx = ContextThemeWrapper(host.get(), R.style.AppTheme)
            val text = "A long paragraph with many spoken words. ".repeat(100)
            val style = TypeStyle.from(ctx, settings(), ReaderTheme.WHITE)
            val view = NovelScrollReaderView(ctx)
            view.bind(listOf(ContentToken.Paragraph(0, text.length, text)), style,
                PageGeometry(320, 480, 16f, 16f, 16f, 16f)) { null }
            host.get().setContentView(view)
            layout(view)
            assertFalse(view.isCharVisible(1000))
            view.followTtsChar(1000)
            layout(view)
            assertTrue(view.isCharVisible(1000))
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `double tapping text seeks without first turning the page`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val ctx = ContextThemeWrapper(host.get(), R.style.AppTheme)
            val text = "Readable text with enough words for many pages. ".repeat(100)
            val style = TypeStyle.from(ctx, settings(), ReaderTheme.WHITE)
            val geo = PageGeometry(320, 480, 16f, 16f, 16f, 16f)
            val pages = Paginator(listOf(ContentToken.Paragraph(0, text.length, text)), geo, style, TextMeasurer(ctx)).paginate()
            val view = NovelReaderView(ctx)
            view.setStyle(style, geo)
            view.setFlipMode(FlipMode.None)
            view.bind(pages, 1)
            var selected: Int? = null
            view.onTextDoubleTap = { selected = it }
            host.get().setContentView(view)
            layout(view)
            val page = view.children.filterIsInstance<PageView>().single { it.currentPage()?.index == 1 }
            val block = page.children.filterIsInstance<ReaderTextBlockView>().first()
            val x = block.left + block.layout.getPrimaryHorizontal(2)
            val y = block.top + (block.layout.getLineTop(0) + block.layout.getLineBottom(0)) / 2f
            fun tap() {
                val down = SystemClock.uptimeMillis()
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).let {
                    view.dispatchTouchEvent(it); it.recycle()
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).let {
                    view.dispatchTouchEvent(it); it.recycle()
                }
            }
            tap()
            assertEquals(1, view.currentPageIndex())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            tap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertNotNull(selected)
            assertEquals(1, view.currentPageIndex())
            assertTrue(selected!! in pages[1].charStart until pages[1].charEnd)
        } finally { host.pause().stop().destroy() }
    }
}
