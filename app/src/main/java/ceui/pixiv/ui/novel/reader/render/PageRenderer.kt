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
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle

/**
 * Canvas painter for a page's non-text chrome — background, chapter titles,
 * images. Text elements are hosted as [ReaderTextBlockView] children by
 * [PageView] and go through TextView's own selection/rendering, so they are
 * NOT drawn here.
 *
 * Persistent overlays on text (annotations, search hits, TTS active range)
 * were previously drawn on the canvas via the paginator's StaticLayout. That
 * path is gone — the next iteration of those features must draw on top of
 * the hosting [ReaderTextBlockView] (via spans or a custom onDraw) so they
 * land on the exact pixel the TextView just rendered.
 */
object PageRenderer {

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
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val jumpBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val jumpTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
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

    /**
     * Paint every non-[PageElement.Text] element on [page]. Text elements are
     * hosted as [ReaderTextBlockView] children on [PageView] and render
     * through TextView directly.
     */
    fun drawNonTextElements(
        canvas: Canvas,
        page: Page,
        paddingLeft: Float,
        style: TypeStyle,
        @Suppress("UNUSED_PARAMETER") overlays: PageOverlays,
        imageSource: ImageBitmapSource,
    ) {
        for (element in page.elements) {
            when (element) {
                is PageElement.Text -> Unit
                is PageElement.Chapter -> drawChapter(canvas, element, paddingLeft, style)
                is PageElement.Image -> drawImage(canvas, element, paddingLeft, style, imageSource)
                is PageElement.Space -> Unit
                is PageElement.Jump -> drawJump(canvas, element, paddingLeft, style)
            }
        }
        // TODO(v3): re-wire annotations / search / TTS overlays on top of the
        // hosting ReaderTextBlockView (via spans or a custom overlay pass) so
        // their rects land on TextView's own pixels. The old canvas path
        // depended on the paginator exposing a StaticLayout, which it no
        // longer does.
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
        dividerPaint.color = style.dividerColor
        canvas.drawLine(
            paddingLeft + element.layout.width * 0.35f,
            underlineY,
            paddingLeft + element.layout.width * 0.65f,
            underlineY,
            dividerPaint,
        )
    }

    private fun drawJump(
        canvas: Canvas,
        element: PageElement.Jump,
        paddingLeft: Float,
        style: TypeStyle,
    ) {
        // Inset the button so it visually reads as an inline control rather
        // than a full-width banner. ~25% of content width on each side keeps
        // the button comfortably wide on phones without overpowering body text.
        val contentWidth = (canvas.width - paddingLeft * 2).coerceAtLeast(1f)
        val sideInset = contentWidth * 0.18f
        val left = paddingLeft + sideInset
        val right = paddingLeft + contentWidth - sideInset
        val rect = RectF(left, element.top, right, element.bottom)
        val radius = (element.bottom - element.top) * 0.5f
        jumpBorderPaint.color = style.linkColor
        canvas.drawRoundRect(rect, radius, radius, jumpBorderPaint)

        jumpTextPaint.color = style.linkColor
        jumpTextPaint.textSize = style.textPaint.textSize
        jumpTextPaint.typeface = style.textPaint.typeface
        val label = ceui.lisa.activities.Shaft.getContext()
            .getString(ceui.lisa.R.string.reader_jump_button, element.target)
        val cy = (element.top + element.bottom) / 2f
        // Vertical center: subtract half the font's visual height (the
        // descent/ascent average is the cleanest baseline for centered text).
        val fm = jumpTextPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, (left + right) / 2f, baseline, jumpTextPaint)
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
            ceui.lisa.activities.Shaft.getContext().getString(ceui.lisa.R.string.reader_image_loading),
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
