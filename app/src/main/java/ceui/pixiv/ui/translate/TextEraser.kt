package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import ceui.pixiv.ui.upscale.OcrTextRegion

/**
 * Erases text from manga pages by filling OCR-detected text regions
 * with their surrounding background color.
 *
 * For manga speech bubbles this produces clean results since backgrounds
 * are typically solid colors (white, black, or a flat tint).
 */
object TextEraser {

    private const val MASK_EXPAND_PX = 4f // expand mask slightly beyond OCR bounds

    /**
     * Erase text from the original bitmap by filling each text region
     * with the dominant background color sampled from its border area.
     *
     * Returns a new Bitmap with text areas filled.
     */
    fun eraseText(original: Bitmap, regions: List<OcrTextRegion>): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (region in regions) {
            val bgColor = sampleBackgroundColor(original, region)
            paint.color = bgColor
            val path = buildRegionPath(region)
            canvas.drawPath(path, paint)
        }
        return result
    }

    /**
     * Build a Path from the region's corner points, expanded slightly outward.
     */
    private fun buildRegionPath(region: OcrTextRegion): Path {
        val corners = region.corners
        if (corners.size < 4) return Path()

        // Compute center
        val cx = corners.map { it.first }.average().toFloat()
        val cy = corners.map { it.second }.average().toFloat()

        // Expand corners outward from center
        val expanded = corners.map { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist < 0.01f) return@map Pair(x, y)
            val scale = (dist + MASK_EXPAND_PX) / dist
            Pair(cx + dx * scale, cy + dy * scale)
        }

        return Path().apply {
            moveTo(expanded[0].first, expanded[0].second)
            for (i in 1 until expanded.size) {
                lineTo(expanded[i].first, expanded[i].second)
            }
            close()
        }
    }

    /**
     * Sample the dominant background color around a text region by reading pixels
     * along the border just outside the bounding box.
     *
     * For typical manga speech bubbles this returns white/light gray.
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, region: OcrTextRegion): Int {
        val corners = region.corners
        if (corners.size < 4) return Color.WHITE

        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val minX = xs.min().toInt()
        val maxX = xs.max().toInt()
        val minY = ys.min().toInt()
        val maxY = ys.max().toInt()

        val sampleMargin = 6
        val w = bitmap.width
        val h = bitmap.height

        val pixels = mutableListOf<Int>()

        // Sample border pixels around the region (outside the text area)
        // Top edge
        val topY = (minY - sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step 2) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, topY))
        }
        // Bottom edge
        val bottomY = (maxY + sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step 2) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, bottomY))
        }
        // Left edge
        val leftX = (minX - sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step 2) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(leftX, sy))
        }
        // Right edge
        val rightX = (maxX + sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step 2) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(rightX, sy))
        }

        if (pixels.isEmpty()) return Color.WHITE

        // Find the most common color (mode) via simple bucketing
        val colorCounts = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            // Quantize to reduce noise: round each channel to nearest 8
            val quantized = quantizeColor(pixel)
            colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
        }
        val dominantQuantized = colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE

        // Return the average of all pixels matching the dominant quantized color
        val matchingPixels = pixels.filter { quantizeColor(it) == dominantQuantized }
        return averageColor(matchingPixels)
    }

    private fun quantizeColor(color: Int): Int {
        val r = (Color.red(color) / 16) * 16
        val g = (Color.green(color) / 16) * 16
        val b = (Color.blue(color) / 16) * 16
        return Color.rgb(r, g, b)
    }

    private fun averageColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (p in pixels) {
            rSum += Color.red(p)
            gSum += Color.green(p)
            bSum += Color.blue(p)
        }
        val n = pixels.size
        return Color.rgb((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }
}
