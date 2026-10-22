package ceui.pixiv.ui.download

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import timber.log.Timber
import androidx.core.graphics.createBitmap

/**
 * 探出「这台设备上哪一档字重真的会把中文画粗」。
 *
 * 起因：vivo V2361A / Android 15 上 `Typeface.create(DEFAULT, 700)` 渲染中文是**常规**，
 * 只有 800 档才出中文粗体；但这是设备特性，不是 Android 的约定。换一台系统字体带 700 粗体
 * （或者根本没有 800）的设备，写死 800 就会退化成「选中和未选中一样粗」，而写死 700 又会
 * 变成另一台设备上不粗。所以这里不猜，直接量。
 *
 * 做法：同一段中文分别用 weight 400/500/600/700/800/900 的 sans-serif 渲染到一张小 Bitmap 上，
 * 量笔画宽度（二值化后水平游程的中位数，CJK 竖笔就是笔画宽）与墨量，先看这台设备「最粗能画到
 * 多少」，再取达到该粗细的最轻一档；一档都比常规粗不了就退回 [Typeface.DEFAULT_BOLD]。
 *
 * 只在第一次用到时跑一次（6 张 60×60 左右的 Bitmap，毫秒级），结果由调用方缓存。
 */
internal object TabTypefaceResolver {

    /** 取样文字：两个高频汉字，竖笔多、横竖都有，够量出稳定中位数。 */
    private const val SAMPLE = "已完"

    private const val BASE_WEIGHT = 400
    private val CANDIDATES = intArrayOf(500, 600, 700, 800, 900)


    /** 按 px 字号缓存：同一字号全进程只量一次（tab 字号固定，实际只有一条）。 */
    private val cache = HashMap<Int, Typeface>()

    /**
     * @param textSizePx 调用方控件当前的文字大小（px）。用真实字号量，避免小字号下笔画差异被抗锯齿吃掉。
     */
    @Synchronized
    fun resolveBold(textSizePx: Float): Typeface {
        val key = textSizePx.toInt()
        cache[key]?.let { return it }
        val resolved = measureBold(textSizePx)
        cache[key] = resolved
        return resolved
    }

    private fun measureBold(textSizePx: Float): Typeface {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Typeface.DEFAULT_BOLD

        val base = measure(Typeface.create(Typeface.DEFAULT, BASE_WEIGHT, false), textSizePx)
        if (base.stroke <= 0) return Typeface.DEFAULT_BOLD

        val measured = ArrayList<Pair<Int, Metric>>(CANDIDATES.size)
        var maxStroke = 0
        val report = StringBuilder()
        for (weight in CANDIDATES) {
            val m = measure(Typeface.create(Typeface.DEFAULT, weight, false), textSizePx)
            measured.add(weight to m)
            report.append(" w$weight=${m.stroke}px/${m.mass}")
            if (m.stroke > maxStroke) maxStroke = m.stroke
        }
        // 先看这台设备「最粗能画到多少」，再取达到这个粗细的**最轻**一档。
        // 设备缺档时更重的档会量成和轻的档一样（实测 700/800/900 都是 7px），取最轻的既能拿到
        // 这台设备真正的最粗效果，又不会无谓地把字重写高。一档都比常规粗不了就退回 DEFAULT_BOLD。
        val pickedWeight = if (maxStroke > base.stroke) {
            measured.firstOrNull { it.second.stroke >= maxStroke }?.first
        } else {
            null
        }
        val result = if (pickedWeight != null) {
            Typeface.create(Typeface.DEFAULT, pickedWeight, false)
        } else {
            Typeface.DEFAULT_BOLD
        }
        Timber.tag(TAG).d(
            "textSize=${textSizePx}px base400=${base.stroke}px/${base.mass}$report -> " +
                "picked=${pickedWeight?.let { "w$it" } ?: "none(DEFAULT_BOLD)"}",
        )
        return result
    }

    private data class Metric(val stroke: Int, val mass: Int)

    private fun measure(typeface: Typeface, textSizePx: Float): Metric {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = textSizePx
            color = Color.WHITE
            isFakeBoldText = false
        }
        val width = ceil(paint.measureText(SAMPLE)).toInt() + PADDING * 2
        val layout = StaticLayout.Builder
            .obtain(SAMPLE, 0, SAMPLE.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        val height = layout.height + PADDING * 2
        if (width <= 0 || height <= 0) return Metric(0, 0)

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.translate(PADDING.toFloat(), PADDING.toFloat())
        layout.draw(canvas)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        var maxAlpha = 0
        for (p in pixels) {
            val a = p ushr 24
            if (a > maxAlpha) maxAlpha = a
        }
        if (maxAlpha == 0) return Metric(0, 0)

        // 阈值跟着文字自身 alpha 走，选没选中、深色浅色都能比。
        val threshold = maxOf(24, maxAlpha / 2)
        var mass = 0
        val runs = ArrayList<Int>(256)
        for (y in 0 until height) {
            var run = 0
            val base = y * width
            for (x in 0 until width) {
                val alpha = pixels[base + x] ushr 24
                mass += alpha
                if (alpha >= threshold) {
                    run++
                } else {
                    if (run >= 2) runs.add(run)
                    run = 0
                }
            }
            if (run >= 2) runs.add(run)
        }
        if (runs.isEmpty()) return Metric(0, mass / 255)
        runs.sort()
        return Metric(runs[runs.size / 2], mass / 255)
    }

    private const val PADDING = 4
    private const val TAG = "TabTypeface"
}
