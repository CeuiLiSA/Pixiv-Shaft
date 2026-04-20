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
