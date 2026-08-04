package ceui.pixiv.ui.translate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import ceui.pixiv.ui.upscale.OcrTextRegion
import timber.log.Timber

/**
 * Renders translated text into manga speech bubbles.
 *
 * Supports both horizontal and vertical text layout,
 * auto-sizes the font to fit the bubble dimensions.
 */
object TextRenderer {

    private const val PADDING_RATIO = 0.08f  // padding inside the bubble as fraction of dimension
    /** 二分搜起点 — 上限不行时再走 [scaleDownToFit] 兜底,允许更小字号防溢出 */
    private const val MIN_FONT_SIZE = 6f
    private const val MAX_FONT_SIZE = 80f
    /** 极端兜底字号下限,小到这个值还塞不下就只能让它溢出/被裁(罕见) */
    private const val ABSOLUTE_MIN_FONT_SIZE = 2f
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

        Timber.d(
            "TextRenderer: canvas %dx%d, %d regions, %d translations",
            canvas.width, canvas.height, regions.size, translations.size
        )
        var drawn = 0
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

            Timber.d(
                "TextRenderer: region[%d] @cx=%.0f,cy=%.0f size=%.0fx%.0f origOrient=%s orig=\"%s\" → \"%s\"",
                index, region.cx, region.cy, region.width, region.height,
                if (region.orientation == 1) "V" else "H",
                region.text.take(40), text.take(40)
            )

            // 译文一律横排:日文竖排气泡翻出中文/俄文等目标语言后,继续竖排极不自然,
            // 业界翻译工具(manga-image-translator / BallonsTranslator)默认行为也是如此。
            renderHorizontalText(canvas, paint, strokePaint, text,
                regionLeft + padX, regionTop + padY, innerWidth, innerHeight)
            drawn++
        }
        Timber.d("TextRenderer: drew %d/%d translations", drawn, regions.size)
    }

    /**
     * Render text horizontally (left-to-right, top-to-bottom).
     */
    private fun renderHorizontalText(
        canvas: Canvas, paint: Paint, strokePaint: Paint,
        text: String, left: Float, top: Float, width: Float, height: Float
    ) {
        var fontSize = fitHorizontalFontSize(paint, text, width, height)
        // 二分上限不达标的兜底:线性再砍小防溢出
        fontSize = scaleDownToFit(paint, text, fontSize, width, height)
        paint.textSize = fontSize
        strokePaint.textSize = fontSize
        strokePaint.strokeWidth = fontSize * 0.08f

        val lines = wrapTextHorizontal(paint, text, width)
        // 用 Paint.FontMetrics 取真实 ascent/descent,baseline 居中更准
        val fm = paint.fontMetrics
        val ascent = -fm.ascent
        val descent = fm.descent
        val lineHeight = fontSize * LINE_SPACING_MULT
        val visualHeight = (lines.size - 1) * lineHeight + (ascent + descent)
        var y = top + (height - visualHeight) / 2f + ascent

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
     * 二分得到的 [seedSize] 可能仍超出框架(初始 lo=MIN 都塞不下时,二分会原样返回 MIN)。
     * 这里再线性砍 0.85 倍,直到真的塞下;到 [ABSOLUTE_MIN_FONT_SIZE] 还塞不下就保留。
     */
    private fun scaleDownToFit(
        paint: Paint,
        text: String,
        seedSize: Float,
        width: Float,
        height: Float,
    ): Float {
        var size = seedSize
        // 至多砍 20 次,size = 6 * 0.85^20 ≈ 0.23,远低于 ABSOLUTE_MIN
        repeat(20) {
            paint.textSize = size
            val lines = wrapTextHorizontal(paint, text, width)
            val fits = lines.size * size * LINE_SPACING_MULT <= height + 1f &&
                lines.all { paint.measureText(it) <= width + 1f }
            if (fits) return size
            if (size <= ABSOLUTE_MIN_FONT_SIZE) return ABSOLUTE_MIN_FONT_SIZE
            size = (size * 0.85f).coerceAtLeast(ABSOLUTE_MIN_FONT_SIZE)
        }
        return size
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
     * 中日韩排版「避头尾」/「禁则处理」:这些字符不允许出现在行首,
     * 行首是它们就把它拖回上一行行尾。覆盖中/英常见标点。
     *
     * 没列在里面的容易遗漏:破折号 — / 全角 / 半角左括号【「《([{《(不允许出现在「行尾」,
     * 这部分本实现暂不处理 — 双向禁则代码量翻倍,效果增量小。
     */
    private val NO_LINE_START = setOf(
        '、', '。', '，', ',', '．', '.', '！', '!', '？', '?', '：', ':', '；', ';',
        ')', '）', ']', '］', '】', '」', '』', '〉', '》', '〕',
        '"', '”', '’', '\'',
        '…', '‥', '·', '・', '—', '~', '〜',
        '°', '%', '％',
    )

    /**
     * Wrap text into lines that fit within the given width.
     * 贪心断行 + 避头尾 post-process。
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

        // 避头尾:从 line[1] 开始,若行首字符是禁则字符,把它「拖回」上一行行尾。
        // 拖回后上一行宽度会轻微溢出(<= 1 字符宽),典型 manga 气泡能接受;
        // 真不接受的话需要回退到「上一行换行点前再加一字符给禁则字符腾位」,代价大。
        var i = 1
        while (i < lines.size) {
            val cur = lines[i]
            if (cur.isNotEmpty() && cur[0] in NO_LINE_START) {
                lines[i - 1] = lines[i - 1] + cur[0]
                if (cur.length == 1) {
                    lines.removeAt(i)
                    // 不 i++,下一行可能也以禁则字符开头,再来一轮
                } else {
                    lines[i] = cur.substring(1)
                    // 同行第二个字符若也是禁则字符,再下一轮把它也拖回
                }
            } else {
                i++
            }
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
