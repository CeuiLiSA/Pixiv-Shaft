package ceui.pixiv.ui.novel.reader.paginate

import android.text.Layout
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.model.TranslationRole

/**
 * Flows [tokens] into a concrete list of [Page]s given the geometry and style.
 *
 * Strategy:
 * - Each image token produces a full-page element (images never share a page
 *   with paragraphs to avoid awkward float / wrap layout).
 * - [ContentToken.Chapter] forces a page break, then emits a centred title on
 *   the next page, and subsequent paragraphs flow beneath.
 * - Paragraphs are measured via [TextMeasurer] — which drives an
 *   `AppCompatTextView` whose settings match the reader's
 *   [ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView] exactly. That is
 *   the whole point of this class: every line break and line height seen
 *   here is what the rendering TextView will reproduce on screen. If the
 *   paragraph doesn't fit, we slice it by character range and the renderer
 *   re-inflates the slice in its own TextView (identical settings → SIMPLE
 *   greedy breaking is prefix-deterministic → same line breaks back out).
 * - Character offsets use the raw-source positions inside `WebNovel.text`,
 *   which keeps progress anchors stable across re-paginations triggered by
 *   setting changes.
 *
 * Must be run on the main thread — [TextMeasurer] drives an actual
 * [android.widget.TextView], which requires a Looper.
 */
class Paginator(
    private val tokens: List<ContentToken>,
    private val geometry: PageGeometry,
    private val style: TypeStyle,
    private val measurer: TextMeasurer,
    private val imageUrlResolver: (ContentToken) -> String? = { null },
) {
    private val pages = ArrayList<Page>(32)
    private val currentElements = ArrayList<PageElement>(16)
    private var currentY: Float = geometry.paddingTop
    private var currentCharStart: Int = 0
    private var currentCharEnd: Int = 0
    private var trackedStartForPage: Boolean = false
    private var currentChapterTitle: String? = null

    fun paginate(): List<Page> {
        if (tokens.isEmpty() || geometry.contentWidth <= 0f || geometry.contentHeight <= 0f) {
            return emptyList()
        }
        for ((i, token) in tokens.withIndex()) {
            processToken(token, next = tokens.getOrNull(i + 1))
        }
        finishPage()
        return pages.toList()
    }

    private fun processToken(token: ContentToken, next: ContentToken?) {
        when (token) {
            is ContentToken.PageBreak -> {
                finishPage()
                currentCharStart = token.sourceEnd
                trackedStartForPage = false
            }

            is ContentToken.UploadedImage -> {
                finishPage()
                emitImagePage(
                    token = token,
                    element = PageElement.Image(
                        top = geometry.paddingTop,
                        bottom = geometry.height - geometry.paddingBottom,
                        absoluteCharStart = token.sourceStart,
                        absoluteCharEnd = token.sourceEnd,
                        imageType = PageElement.Image.ImageType.UploadedImage,
                        resourceId = token.imageId,
                        pageIndexInIllust = 0,
                        imageUrl = imageUrlResolver(token),
                    ),
                )
            }

            is ContentToken.PixivImage -> {
                finishPage()
                emitImagePage(
                    token = token,
                    element = PageElement.Image(
                        top = geometry.paddingTop,
                        bottom = geometry.height - geometry.paddingBottom,
                        absoluteCharStart = token.sourceStart,
                        absoluteCharEnd = token.sourceEnd,
                        imageType = PageElement.Image.ImageType.PixivImage,
                        resourceId = token.illustId,
                        pageIndexInIllust = token.pageIndex,
                        imageUrl = imageUrlResolver(token),
                        isMix = token.isMix,
                    ),
                )
            }

            is ContentToken.Chapter -> {
                finishPage()
                currentChapterTitle = token.title
                emitChapterHeading(token)
            }

            is ContentToken.Paragraph -> {
                // 紧跟自己双语译文的原文段落收紧段后距，原文/译文读起来是一组（issue #864）。
                val followedByTranslation =
                    (next as? ContentToken.Paragraph)?.translationRole == TranslationRole.Interlinear
                emitParagraph(
                    token,
                    gapAfter = if (followedByTranslation) style.translationGapPx else style.paragraphSpacingPx,
                )
            }

            is ContentToken.BlankLine -> {
                if (currentElements.isNotEmpty()) {
                    val gap = style.paragraphSpacingPx.coerceAtLeast(style.textLineHeightPx.toFloat())
                    val space = PageElement.Space(
                        top = currentY,
                        bottom = currentY + gap,
                        absoluteCharStart = token.sourceStart,
                        absoluteCharEnd = token.sourceEnd,
                    )
                    currentElements += space
                    currentY += gap
                    currentCharEnd = token.sourceEnd
                }
            }

            is ContentToken.Jump -> emitJump(token)
        }
    }

    private fun emitJump(token: ContentToken.Jump) {
        // Button is a fixed-height row with vertical breathing room. Keep its
        // height tied to textSize so it scales with reader font settings, and
        // pad with paragraphSpacing on either side so the button doesn't
        // crowd the surrounding text.
        val buttonHeight = style.textPaint.textSize * 2.4f
        val gap = style.paragraphSpacingPx
        val totalHeight = buttonHeight + gap * 2f
        val remaining = (geometry.height - geometry.paddingBottom) - currentY
        if (remaining < totalHeight && currentElements.isNotEmpty()) {
            finishPage()
        }
        currentY += gap
        val element = PageElement.Jump(
            top = currentY,
            bottom = currentY + buttonHeight,
            absoluteCharStart = token.sourceStart,
            absoluteCharEnd = token.sourceEnd,
            target = token.target,
        )
        currentElements += element
        ensureStartTracked(token.sourceStart)
        currentY += buttonHeight + gap
        currentCharEnd = token.sourceEnd
    }

    private fun emitImagePage(token: ContentToken, element: PageElement.Image) {
        pages += Page(
            index = pages.size,
            elements = listOf(element),
            charStart = token.sourceStart,
            charEnd = token.sourceEnd,
            chapterTitle = currentChapterTitle,
        )
        currentCharStart = token.sourceEnd
        trackedStartForPage = false
    }

    private fun emitChapterHeading(token: ContentToken.Chapter) {
        val width = geometry.contentWidth.toInt()
        if (width <= 0) return
        currentY += style.chapterTopGapPx
        // Chapter titles are painted directly on the page canvas by
        // PageRenderer, not hosted in a TextView, so there's no rendering
        // path to match. Use the lightweight StaticLayout builder.
        val layout = TextMeasurer.buildStaticLayout(
            text = token.title.ifEmpty { "  " },
            paint = style.chapterPaint,
            width = width,
            lineSpacingMultiplier = 1.15f,
            lineSpacingExtra = 0f,
            alignment = Layout.Alignment.ALIGN_CENTER,
        )
        val height = layout.height.toFloat()
        // If the chapter + at least one text line can't fit, push to next page.
        val minimumRoomNeeded = height + style.textPaint.textSize + style.chapterBottomGapPx
        val remaining = (geometry.height - geometry.paddingBottom) - currentY
        if (remaining < minimumRoomNeeded && currentElements.isNotEmpty()) {
            finishPage()
            currentY += style.chapterTopGapPx
        }
        val element = PageElement.Chapter(
            top = currentY,
            bottom = currentY + height,
            absoluteCharStart = token.sourceStart,
            absoluteCharEnd = token.sourceEnd,
            title = token.title,
            layout = layout,
        )
        currentElements += element
        ensureStartTracked(token.sourceStart)
        currentY += height + style.chapterBottomGapPx
        currentCharEnd = token.sourceEnd
    }

    private fun emitParagraph(token: ContentToken.Paragraph, gapAfter: Float) {
        val width = geometry.contentWidth.toInt()
        if (width <= 0) return
        // 双语对照的译文降一级排版、不缩进；仅译文顶替原文，按正文样式排。
        val interlinear = token.translationRole == TranslationRole.Interlinear
        val indent = if (interlinear) 0 else style.firstLineIndentPx.toInt()
        val source = if (indent > 0) TextMeasurer.withFirstLineIndent(token.text, indent) else token.text
        val layout = measurer.measure(
            text = source,
            paint = if (interlinear) style.translationPaint else style.textPaint,
            width = width,
            lineSpacingMultiplier = style.lineSpacingMultiplier,
            lineSpacingExtra = style.lineSpacingExtra,
        )
        val total = layout.lineCount
        // Snapshot the slice data we need before advancing — the measurer's
        // internal TextView is reused, so its `layout` becomes stale on the
        // next emitParagraph() call.
        var cursor = 0
        while (cursor < total) {
            val remainingHeight = (geometry.height - geometry.paddingBottom) - currentY
            if (remainingHeight <= 0f) {
                finishPage()
                continue
            }
            val startTop = layout.getLineTop(cursor)
            // Does even the *first* line fit? If not, flush the page and
            // retry on a fresh one. The empty-page guard prevents an
            // infinite loop in the pathological "font bigger than page"
            // case — there we reluctantly accept a single overflowing line.
            val firstLineBottom = layout.getLineBottom(cursor).toFloat()
            val firstLineHeight = firstLineBottom - startTop
            if (firstLineHeight > remainingHeight && currentElements.isNotEmpty()) {
                finishPage()
                continue
            }
            var linesFit = 0
            var pxUsed = 0f
            for (i in cursor until total) {
                val pxIfIncluded = (layout.getLineBottom(i) - startTop).toFloat()
                if (pxIfIncluded > remainingHeight && linesFit > 0) break
                linesFit = i - cursor + 1
                pxUsed = pxIfIncluded
                if (pxIfIncluded > remainingHeight) {
                    // Reached only via the empty-page fallback above — a
                    // single line that won't fit even on a blank page. Emit
                    // anyway so we don't drop content.
                    break
                }
            }
            if (linesFit == 0) {
                finishPage()
                continue
            }

            val startCharInLayout = layout.getLineStart(cursor).coerceIn(0, token.text.length)
            val endCharInLayout = layout.getLineEnd(cursor + linesFit - 1).coerceIn(0, token.text.length)
            val absoluteStart: Int
            val absoluteEnd: Int
            when (token.translationRole) {
                TranslationRole.None -> {
                    absoluteStart = token.textSourceStart + startCharInLayout
                    absoluteEnd = token.textSourceStart + endCharInLayout
                }
                // 双语译文不在源文本里：钉在零宽度锚点上，不虚构字符区间。
                TranslationRole.Interlinear -> {
                    absoluteStart = token.textSourceStart
                    absoluteEnd = token.textSourceStart
                }
                // 仅译文顶替整段原文：切片按比例映射回原文区间。跨页的段落每页各占一截，
                // 续页的页首才与前一页的 charEnd 区分得开，重排版能落回续页。
                TranslationRole.Replacement -> {
                    val span = (token.sourceEnd - token.textSourceStart).toLong()
                    val length = token.text.length.coerceAtLeast(1)
                    absoluteStart = token.textSourceStart + (startCharInLayout * span / length).toInt()
                    absoluteEnd = token.textSourceStart + (endCharInLayout * span / length).toInt()
                }
            }
            val sliceText = token.text.substring(startCharInLayout, endCharInLayout)

            val element = PageElement.Text(
                top = currentY,
                bottom = currentY + pxUsed,
                absoluteCharStart = absoluteStart,
                absoluteCharEnd = absoluteEnd,
                text = sliceText,
                paragraphIndex = token.sourceStart,
                isFirstLineOfParagraph = cursor == 0,
                isLastLineOfParagraph = (cursor + linesFit) == total,
                lineCount = linesFit,
                translationRole = token.translationRole,
            )
            currentElements += element
            ensureStartTracked(absoluteStart)
            currentY += pxUsed
            currentCharEnd = absoluteEnd
            cursor += linesFit

            if (cursor < total) {
                finishPage()
            } else {
                currentY += gapAfter
            }
        }
    }

    private fun ensureStartTracked(start: Int) {
        if (!trackedStartForPage) {
            currentCharStart = start
            trackedStartForPage = true
        }
    }

    private fun finishPage() {
        if (currentElements.isEmpty()) {
            currentY = geometry.paddingTop
            trackedStartForPage = false
            return
        }
        pages += Page(
            index = pages.size,
            elements = currentElements.toList(),
            charStart = currentCharStart,
            charEnd = currentCharEnd,
            chapterTitle = currentChapterTitle,
        )
        currentElements.clear()
        currentY = geometry.paddingTop
        currentCharStart = currentCharEnd
        trackedStartForPage = false
    }
}
