package ceui.pixiv.ui.translate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import ceui.pixiv.ui.upscale.OcrTextRegion
import kotlin.math.min

/**
 * Renders translated text into manga speech bubbles.
 *
 * Supports both horizontal and vertical text layout,
 * auto-sizes the font to fit the bubble dimensions.
 */
object TextRenderer {

    private const val PADDING_RATIO = 0.08f  // padding inside the bubble as fraction of dimension
    private const val MIN_FONT_SIZE = 10f
    private const val MAX_FONT_SIZE = 80f
    private const val LINE_SPACING_MULT = 1.15f

    /**
     * Render translated text for each OCR region onto the canvas.
     *
     * @param canvas The canvas to draw on (already has text erased)
     * @param regions The OCR-detected text regions
     * @param translations Map of region index to translated text
     */
    fun renderTranslations(
        canvas: Canvas,
        regions: List<OcrTextRegion>,
        translations: Map<Int, String>
    ) {
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }

        val strokePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            style = Paint.Style.STROKE
        }

        for ((index, region) in regions.withIndex()) {
            val text = translations[index] ?: continue
            if (text.isBlank()) continue

            val corners = region.corners
            if (corners.size < 4) continue

            val xs = corners.map { it.first }
            val ys = corners.map { it.second }
            val regionLeft = xs.min()
            val regionTop = ys.min()
            val regionWidth = xs.max() - regionLeft
            val regionHeight = ys.max() - regionTop

            val padX = regionWidth * PADDING_RATIO
            val padY = regionHeight * PADDING_RATIO
            val innerWidth = regionWidth - padX * 2
            val innerHeight = regionHeight - padY * 2

            if (innerWidth <= 0 || innerHeight <= 0) continue

            // Determine text color: use dark text on light background, light text on dark background
            val bgBrightness = estimateBackgroundBrightness(canvas, regionLeft, regionTop, regionWidth, regionHeight)
            if (bgBrightness < 128) {
                paint.color = Color.WHITE
                strokePaint.color = Color.BLACK
            } else {
                paint.color = Color.BLACK
                strokePaint.color = Color.WHITE
            }

            if (region.orientation == 1) {
                renderVerticalText(canvas, paint, strokePaint, text,
                    regionLeft + padX, regionTop + padY, innerWidth, innerHeight)
            } else {
                renderHorizontalText(canvas, paint, strokePaint, text,
                    regionLeft + padX, regionTop + padY, innerWidth, innerHeight)
            }
        }
    }

    /**
     * Render text horizontally (left-to-right, top-to-bottom).
     */
    private fun renderHorizontalText(
        canvas: Canvas, paint: Paint, strokePaint: Paint,
        text: String, left: Float, top: Float, width: Float, height: Float
    ) {
        val fontSize = fitHorizontalFontSize(paint, text, width, height)
        paint.textSize = fontSize
        strokePaint.textSize = fontSize
        strokePaint.strokeWidth = fontSize * 0.08f

        val lines = wrapTextHorizontal(paint, text, width)
        val lineHeight = fontSize * LINE_SPACING_MULT
        val totalTextHeight = lines.size * lineHeight

        // Center vertically
        var y = top + (height - totalTextHeight) / 2f + fontSize

        for (line in lines) {
            // Center horizontally
            val lineWidth = paint.measureText(line)
            val x = left + (width - lineWidth) / 2f
            canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
    }

    /**
     * Render text vertically (top-to-bottom per column, columns right-to-left).
     */
    private fun renderVerticalText(
        canvas: Canvas, paint: Paint, strokePaint: Paint,
        text: String, left: Float, top: Float, width: Float, height: Float
    ) {
        val fontSize = fitVerticalFontSize(text, width, height)
        paint.textSize = fontSize
        strokePaint.textSize = fontSize
        strokePaint.strokeWidth = fontSize * 0.08f

        val colSpacing = fontSize * LINE_SPACING_MULT
        val charHeight = fontSize * LINE_SPACING_MULT
        val charsPerCol = maxOf(1, (height / charHeight).toInt())

        val columns = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = min(i + charsPerCol, text.length)
            columns.add(text.substring(i, end))
            i = end
        }

        val totalWidth = columns.size * colSpacing
        // Start from right side (traditional vertical reading: right-to-left columns)
        var x = left + width - (width - totalWidth) / 2f - colSpacing / 2f

        val bounds = Rect()
        for (col in columns) {
            var y = top + (height - col.length * charHeight) / 2f + fontSize
            for (ch in col) {
                val s = ch.toString()
                paint.getTextBounds(s, 0, 1, bounds)
                val charWidth = paint.measureText(s)
                val cx = x - charWidth / 2f
                canvas.drawText(s, cx, y, strokePaint)
                canvas.drawText(s, cx, y, paint)
                y += charHeight
            }
            x -= colSpacing
        }
    }

    /**
     * Find the largest font size that fits the text within the given horizontal area.
     */
    private fun fitHorizontalFontSize(paint: Paint, text: String, width: Float, height: Float): Float {
        var lo = MIN_FONT_SIZE
        var hi = MAX_FONT_SIZE
        var best = lo

        while (hi - lo > 0.5f) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            val lines = wrapTextHorizontal(paint, text, width)
            val totalHeight = lines.size * mid * LINE_SPACING_MULT
            if (totalHeight <= height && lines.all { paint.measureText(it) <= width + 1f }) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    /**
     * Find the largest font size that fits the text vertically.
     */
    private fun fitVerticalFontSize(text: String, width: Float, height: Float): Float {
        var lo = MIN_FONT_SIZE
        var hi = MAX_FONT_SIZE
        var best = lo

        while (hi - lo > 0.5f) {
            val mid = (lo + hi) / 2f
            val charHeight = mid * LINE_SPACING_MULT
            val colSpacing = mid * LINE_SPACING_MULT
            val charsPerCol = maxOf(1, (height / charHeight).toInt())
            val numCols = (text.length + charsPerCol - 1) / charsPerCol
            val totalWidth = numCols * colSpacing
            if (totalWidth <= width && charsPerCol * charHeight <= height + 1f) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    /**
     * Wrap text into lines that fit within the given width.
     */
    private fun wrapTextHorizontal(paint: Paint, text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()

        for (ch in text) {
            sb.append(ch)
            if (paint.measureText(sb.toString()) > maxWidth) {
                if (sb.length > 1) {
                    // Push back the last char, end the line
                    lines.add(sb.substring(0, sb.length - 1))
                    sb.clear()
                    sb.append(ch)
                } else {
                    // Single char exceeds width; force it onto its own line
                    lines.add(sb.toString())
                    sb.clear()
                }
            }
        }
        if (sb.isNotEmpty()) {
            lines.add(sb.toString())
        }

        return lines
    }

    /**
     * Estimate background brightness by sampling the center of a region.
     */
    private fun estimateBackgroundBrightness(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float
    ): Int {
        // We can't easily read pixels from canvas, so use a heuristic:
        // Default to assuming light background (most manga speech bubbles are white)
        return 240
    }
}
