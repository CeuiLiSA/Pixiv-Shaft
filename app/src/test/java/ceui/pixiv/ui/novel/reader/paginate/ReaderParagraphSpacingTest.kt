package ceui.pixiv.ui.novel.reader.paginate

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.render.NovelScrollReaderView
import ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** #1140: compare the real TextView layouts, not a second copy of the spacing formula. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderParagraphSpacingTest {
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

    private fun measuredLineHeight(style: TypeStyle): Int {
        val layout = TextMeasurer(context).measure(
            "正文 Text\n正文 Text", style.textPaint, 600,
            style.lineSpacingMultiplier, style.lineSpacingExtra,
        )
        assertEquals(2, layout.lineCount)
        val height = layout.getLineBottom(0) - layout.getLineTop(0)
        assertEquals(height, layout.getLineBottom(1) - layout.getLineTop(1))
        return height
    }

    @Test fun `one paragraph spacing line equals one rendered body line for all typography`() {
        for (size in listOf(12, 18, 36)) {
            for (spacing in listOf(1f, 1.6f, 2.8f)) {
                for (font in listOf("system", "preset_serif", "preset_monospace")) {
                    val style = TypeStyle.from(context, settings(size, spacing, font = font), ReaderTheme.WHITE)
                    assertEquals("size=$size spacing=$spacing font=$font",
                        measuredLineHeight(style).toFloat(), style.paragraphSpacingPx, 0f)
                }
            }
        }
    }

    @Test fun `merged pages match pagination budgets including zero and fractional gaps`() {
        val tokens = (0 until 30).map { i ->
            val text = "正文第${i}段 Text with enough words to wrap on a narrow page。"
            ContentToken.Paragraph(i * 100, i * 100 + text.length, text)
        }
        for (theme in listOf(ReaderTheme.WHITE, ReaderTheme.NIGHT)) {
            for (width in listOf(160, 480)) {
                for (gap in listOf(0f, 0.1f, 0.6f, 0.8f, 1f, 2.5f)) {
                    val style = TypeStyle.from(context, settings(gap = gap), theme)
                    val pages = Paginator(tokens, geometry(width), style, TextMeasurer(context)).paginate()
                    assertTrue(pages.size > 1)
                    for (page in pages) {
                        val elements = page.textElements
                        val view = ReaderTextBlockView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                            bindTextGroup(elements, style)
                        }
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        val budget = (elements.last().bottom - elements.first().top).roundToInt()
                        assertEquals("width=$width gap=$gap page=${page.index}", budget, view.layout.height)
                        assertTrue(elements.last().bottom <= 480)
                        if (gap == 0f) assertFalse(view.text.contains('\u200B'))
                    }
                    assertEquals(tokens.sumOf { it.text.length },
                        pages.sumOf { p -> p.textElements.sumOf { it.text.length } })
                }
            }
        }
    }

    @Test fun `scroll paragraphs and source blank lines use the same body line and gap`() {
        val tokens = listOf(
            ContentToken.Paragraph(0, 2, "正文"),
            ContentToken.BlankLine(2, 3),
            ContentToken.Paragraph(3, 5, "正文"),
        )
        for (gap in listOf(0f, 0.1f, 1f, 2.5f)) {
            val style = TypeStyle.from(context, settings(spacing = 2.8f, gap = gap), ReaderTheme.WHITE)
            val lineHeight = measuredLineHeight(style)
            val scroll = NovelScrollReaderView(context).apply { bind(tokens, style, geometry()) { null } }
            val adapter = requireNotNull(scroll.adapter)
            val views = tokens.indices.map { i ->
                val holder = adapter.createViewHolder(scroll, adapter.getItemViewType(i))
                adapter.bindViewHolder(holder, i)
                holder.itemView.apply {
                    measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                }
            }
            val paragraph = views.first() as TextView
            assertEquals(lineHeight, paragraph.layout.height)
            assertEquals(style.paragraphSpacingPx.roundToInt(),
                (paragraph.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            val space = Paginator(tokens, geometry(), style, TextMeasurer(context)).paginate()
                .single().elements.filterIsInstance<PageElement.Space>().single()
            val expectedBlank = maxOf(lineHeight, style.paragraphSpacingPx.roundToInt())
            assertEquals(expectedBlank.toFloat(), space.bottom - space.top, 0f)
            assertEquals(expectedBlank, views[1].layoutParams.height)
        }
    }

    @Test fun `rendered gaps follow the selected fraction of a body line after font scaling`() {
        val tokens = listOf(ContentToken.Paragraph(0, 2, "正文"), ContentToken.Paragraph(3, 5, "正文"))
        for (fontScale in listOf(1f, 2f)) {
            val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
            val scaledContext = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme)
            for (spacing in listOf(1f, 1.6f, 2.8f)) {
                val gaps = listOf(0f, 0.8f, 1f).map { gap ->
                    val style = TypeStyle.from(scaledContext,
                        settings(spacing = spacing, gap = gap).copy(firstLineIndent = 2), ReaderTheme.WHITE)
                    val page = Paginator(tokens, geometry().copy(height = 2000), style, TextMeasurer(scaledContext))
                        .paginate().single()
                    val view = ReaderTextBlockView(scaledContext).apply {
                        layoutParams = ViewGroup.LayoutParams(480, ViewGroup.LayoutParams.WRAP_CONTENT)
                        bindTextGroup(page.textElements, style)
                        measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    }
                    val layout = view.layout
                    val nextLine = layout.getLineForOffset(view.text.toString().lastIndexOf("正文"))
                    val bodyHeight = layout.getLineBottom(0) - layout.getLineTop(0)
                    val actualGap = layout.getLineTop(nextLine) - layout.getLineBottom(0)
                    assertEquals("fontScale=$fontScale spacing=$spacing gap=$gap",
                        (bodyHeight * gap).roundToInt(), actualGap)
                    actualGap
                }
                assertEquals(0, gaps[0])
                assertTrue(gaps[1] > gaps[0])
                assertTrue(gaps[2] > gaps[1])
            }
        }
    }

    private fun geometry(width: Int = 480) = PageGeometry(width, 480, 0f, 0f, 0f, 0f)
}
