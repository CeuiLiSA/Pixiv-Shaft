package ceui.pixiv.ui.novel.reader.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle

/**
 * Stateless canvas painter for a single page. Given a page, style, and overlays,
 * it renders background + text + images + chapter titles + highlights.
 *
 * All drawing is synchronous; async resources (image bitmaps) are looked up via
 * [ImageBitmapSource] and fall back to a placeholder if not yet loaded.
 */
object PageRenderer {

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val imageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        style: TypeStyle,
        backgroundBitmap: Bitmap? = null,
    ) {
        if (backgroundBitmap != null) {
            backgroundPaint.shader = BitmapShader(backgroundBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            backgroundPaint.shader = null
        } else {
            backgroundPaint.color = style.backgroundColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        }
    }

    fun drawPage(
        canvas: Canvas,
        page: Page,
        paddingLeft: Float,
        style: TypeStyle,
        overlays: PageOverlays,
        imageSource: ImageBitmapSource,
    ) {
        for (element in page.elements) {
            when (element) {
                is PageElement.Text -> drawText(canvas, element, paddingLeft, style, overlays)
                is PageElement.Chapter -> drawChapter(canvas, element, paddingLeft, style)
                is PageElement.Image -> drawImage(canvas, element, paddingLeft, style, imageSource)
                is PageElement.Space -> Unit // pure whitespace
            }
        }
    }

    /**
     * Paint every non-[PageElement.Text] element on [page]. Used by [PageView]
     * which hosts Text elements as native [ReaderTextBlockView] children for
     * system-provided selection — the canvas path handles chapters/images/etc.
     */
    fun drawNonTextElements(
        canvas: Canvas,
        page: Page,
        paddingLeft: Float,
        style: TypeStyle,
        overlays: PageOverlays,
        imageSource: ImageBitmapSource,
    ) {
        for (element in page.elements) {
            when (element) {
                is PageElement.Text -> Unit
                is PageElement.Chapter -> drawChapter(canvas, element, paddingLeft, style)
                is PageElement.Image -> drawImage(canvas, element, paddingLeft, style, imageSource)
                is PageElement.Space -> Unit
            }
        }
        // Overlays (search / annotations / TTS) still render on the canvas
        // because they can span multiple text blocks — we overlay them above
        // the non-text layer but underneath the TextView children. Selection
        // rendering is intentionally dropped here: TextView draws it natively.
        drawCanvasOverlays(canvas, page, paddingLeft, overlays)
    }

    private fun drawCanvasOverlays(
        canvas: Canvas,
        page: Page,
        paddingLeft: Float,
        overlays: PageOverlays,
    ) {
        for (element in page.elements) {
            if (element !is PageElement.Text) continue
            val layout = element.layout
            val save = canvas.save()
            try {
                val startTop = layout.getLineTop(element.startLine)
                val endBottom = layout.getLineBottom(element.endLineExclusive - 1)
                canvas.translate(paddingLeft, element.top - startTop)
                canvas.clipRect(
                    0f,
                    startTop.toFloat(),
                    layout.width.toFloat(),
                    endBottom.toFloat(),
                )
                for (ann in overlays.annotations) {
                    val rect = rectForRangeIn(element, layout, ann.absoluteStart, ann.absoluteEnd) ?: continue
                    highlightPaint.color = ann.color
                    canvas.drawRoundRect(rect, 4f, 4f, highlightPaint)
                }
                for (hit in overlays.searchHits) {
                    val rect = rectForRangeIn(element, layout, hit.absoluteStart, hit.absoluteEnd) ?: continue
                    searchPaint.color = if (hit.isCurrent) 0xFF5B6EFF.toInt() else hit.color
                    searchPaint.alpha = if (hit.isCurrent) 128 else 80
                    canvas.drawRoundRect(rect, 4f, 4f, searchPaint)
                }
                overlays.ttsActiveRange?.let { range ->
                    val rect = rectForRangeIn(element, layout, range.first, range.last + 1) ?: return@let
                    highlightPaint.color = 0x3300FF88.toInt()
                    canvas.drawRoundRect(rect, 4f, 4f, highlightPaint)
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    private fun drawText(
        canvas: Canvas,
        element: PageElement.Text,
        paddingLeft: Float,
        style: TypeStyle,
        overlays: PageOverlays,
    ) {
        val layout = element.layout
        if (element.lineCount <= 0) return
        val startTop = layout.getLineTop(element.startLine)
        val endBottom = layout.getLineBottom(element.endLineExclusive - 1)
        val canvasSave = canvas.save()
        try {
            canvas.translate(paddingLeft, element.top - startTop)
            canvas.clipRect(
                0f,
                startTop.toFloat(),
                layout.width.toFloat(),
                endBottom.toFloat(),
            )
            drawHighlightsUnder(canvas, element, layout, style, overlays)
            layout.draw(canvas)
            drawHighlightsOver(canvas, element, layout, style, overlays)
        } finally {
            canvas.restoreToCount(canvasSave)
        }
    }

    private fun drawHighlightsUnder(
        canvas: Canvas,
        element: PageElement.Text,
        layout: Layout,
        style: TypeStyle,
        overlays: PageOverlays,
    ) {
        // Persistent annotations first (soft background) ...
        for (ann in overlays.annotations) {
            val rect = rectForRangeIn(element, layout, ann.absoluteStart, ann.absoluteEnd) ?: continue
            highlightPaint.color = ann.color
            canvas.drawRoundRect(rect, 4f, 4f, highlightPaint)
        }
        // ... then search matches (stronger) ...
        for (hit in overlays.searchHits) {
            val rect = rectForRangeIn(element, layout, hit.absoluteStart, hit.absoluteEnd) ?: continue
            searchPaint.color = if (hit.isCurrent) style.accentColor else hit.color
            searchPaint.alpha = if (hit.isCurrent) 128 else 80
            canvas.drawRoundRect(rect, 4f, 4f, searchPaint)
        }
        // ... finally the active selection on top of the rest.
        overlays.selection?.takeIf { !it.isCollapsed }?.let { sel ->
            val rect = rectForRangeIn(element, layout, sel.absoluteStart, sel.absoluteEnd)
            if (rect != null) {
                selectionPaint.color = style.selectionColor
                canvas.drawRect(rect, selectionPaint)
            }
        }
    }

    private fun drawHighlightsOver(
        canvas: Canvas,
        element: PageElement.Text,
        layout: Layout,
        style: TypeStyle,
        overlays: PageOverlays,
    ) {
        overlays.ttsActiveRange?.let { range ->
            val rect = rectForRangeIn(element, layout, range.first, range.last + 1) ?: return@let
            val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.FILL
                color = 0x3300FF88.toInt()
            }
            canvas.drawRoundRect(rect, 4f, 4f, overlay)
        }
    }

    /**
     * Convert an absolute character range ([absStart], [absEnd]) to a [RectF]
     * inside the paragraph [layout], restricted to the line range carried by
     * [element]. Returns null when the range doesn't intersect this element.
     */
    private fun rectForRangeIn(
        element: PageElement.Text,
        layout: Layout,
        absStart: Int,
        absEnd: Int,
    ): RectF? {
        if (absEnd <= absStart) return null
        val elementStart = element.absoluteCharStart
        val elementEnd = element.absoluteCharEnd
        val clampedStart = absStart.coerceIn(elementStart, elementEnd)
        val clampedEnd = absEnd.coerceIn(elementStart, elementEnd)
        if (clampedEnd <= clampedStart) return null

        val paragraphStart = elementStart - (layout.getLineStart(element.startLine).coerceAtLeast(0))
        val localStart = clampedStart - paragraphStart
        val localEnd = clampedEnd - paragraphStart
        val totalLen = layout.text.length
        if (localStart < 0 || localEnd > totalLen || localEnd <= localStart) return null

        val lineStart = layout.getLineForOffset(localStart)
        val lineEnd = layout.getLineForOffset((localEnd - 1).coerceAtLeast(localStart))
        if (lineEnd < element.startLine || lineStart >= element.endLineExclusive) return null

        val boundLine0 = lineStart.coerceAtLeast(element.startLine)
        val boundLine1 = lineEnd.coerceAtMost(element.endLineExclusive - 1)
        val top = layout.getLineTop(boundLine0).toFloat()
        val bottom = layout.getLineBottom(boundLine1).toFloat()

        // Single-line fast path
        return if (boundLine0 == boundLine1) {
            val xStart = layout.getPrimaryHorizontal(localStart)
            val xEnd = layout.getPrimaryHorizontal(localEnd)
            val left = minOf(xStart, xEnd)
            val right = maxOf(xStart, xEnd)
            RectF(left, top, right, bottom)
        } else {
            // Multi-line: we use the full width as an approximation so highlights
            // tile visually. Renderers that need char-precision per line should
            // break the range into per-line rects (future improvement).
            RectF(0f, top, layout.width.toFloat(), bottom)
        }
    }

    private fun drawChapter(
        canvas: Canvas,
        element: PageElement.Chapter,
        paddingLeft: Float,
        style: TypeStyle,
    ) {
        val canvasSave = canvas.save()
        try {
            canvas.translate(paddingLeft, element.top)
            element.layout.draw(canvas)
        } finally {
            canvas.restoreToCount(canvasSave)
        }
        // Divider under chapter title
        val underlineY = element.bottom + style.chapterBottomGapPx * 0.35f
        val dividerColor = style.dividerColor
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = dividerColor
        }
        canvas.drawLine(
            paddingLeft + element.layout.width * 0.35f,
            underlineY,
            paddingLeft + element.layout.width * 0.65f,
            underlineY,
            paint,
        )
    }

    private fun drawImage(
        canvas: Canvas,
        element: PageElement.Image,
        paddingLeft: Float,
        style: TypeStyle,
        imageSource: ImageBitmapSource,
    ) {
        val contentWidth = (canvas.width - paddingLeft * 2).coerceAtLeast(1f)
        val left = paddingLeft
        val right = left + contentWidth
        val top = element.top
        val bottom = element.bottom

        val bitmap = imageSource.bitmapFor(element)
        if (bitmap == null) {
            drawImagePlaceholder(canvas, left, top, right, bottom, style)
            return
        }

        val targetRect = RectF(left, top, right, bottom)
        val matrix = Matrix()
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        when (style.imageScaleMode) {
            ImageScaleMode.Fill -> {
                matrix.setRectToRect(RectF(src), targetRect, Matrix.ScaleToFit.FILL)
            }
            ImageScaleMode.Fit -> {
                val scale = minOf(
                    targetRect.width() / bitmap.width,
                    targetRect.height() / bitmap.height,
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val dx = targetRect.left + (targetRect.width() - drawWidth) / 2f
                val dy = targetRect.top + (targetRect.height() - drawHeight) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
            ImageScaleMode.Original -> {
                val dx = targetRect.left + (targetRect.width() - bitmap.width) / 2f
                val dy = targetRect.top + (targetRect.height() - bitmap.height) / 2f
                matrix.postTranslate(dx, dy)
            }
        }
        val clipSave = canvas.save()
        canvas.clipRect(targetRect)
        canvas.drawBitmap(bitmap, matrix, null)
        canvas.restoreToCount(clipSave)
    }

    private fun drawImagePlaceholder(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        style: TypeStyle,
    ) {
        placeholderPaint.color = style.dividerColor
        placeholderPaint.pathEffect = null
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, 12f, 12f, placeholderPaint)
        placeholderTextPaint.color = style.secondaryTextColor
        placeholderTextPaint.textSize = style.textSize
        canvas.drawText(
            "图片加载中…",
            (left + right) / 2f,
            (top + bottom) / 2f + placeholderTextPaint.textSize / 3f,
            placeholderTextPaint,
        )
    }
}

interface ImageBitmapSource {
    fun bitmapFor(element: PageElement.Image): Bitmap?

    companion object {
        val EMPTY = object : ImageBitmapSource {
            override fun bitmapFor(element: PageElement.Image): Bitmap? = null
        }
    }
}
