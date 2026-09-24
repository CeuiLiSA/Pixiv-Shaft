package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import android.graphics.Color
import ceui.pixiv.ui.upscale.OcrTextRegion
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把 OCR 文本框扩到气泡内部的可写 AABB。
 *
 * 思路:CTD 给的 OCR region 紧贴原文字像素,典型只占气泡内部 30%;翻译后中文字符
 * 比原文长 1.5-2 倍是常态,塞回原 OCR 框字号会被压成蚂蚁。这里以 AABB 为种子,
 * 向上下左右逐行/列扩展 — 一旦那一行/列大部分像素不是气泡背景色,就当作碰到了
 * 气泡轮廓(典型 manga 是黑边),停下。
 *
 * 假设:气泡内部基本单色(纯白/淡灰),轮廓是连续非背景像素。
 * 失效场景:气泡内有 screentone / 渐变 / 角色透写,扩展会过早或过晚停;
 * 极端 ellipsoidal 气泡四角不是 bg 但行采样仍 >= [ROW_BG_RATIO] 会越界一点点。
 *
 * 像素常量按参考分辨率调过,更高分辨率的底图由调用方传 `pxScale` 等比放大。
 */
object BubbleAreaFinder {

    /** 单方向最大扩展像素 — 够覆盖典型 bubble 半径,防止扩到隔壁气泡。 */
    private const val MAX_EXPAND_PX = 300

    /** 像素与 bg 单通道差超过这个,判定为「非 bg」。 */
    private const val DIFF_THRESHOLD = 48

    /** 一行/列里 bg 像素占比 ≥ 这个,才允许继续扩。0.8 容忍少量斑点/锯齿。 */
    private const val ROW_BG_RATIO = 0.80f

    /** 行/列采样步长 — 每 N 个像素抽一个,平衡精度和速度。 */
    private const val SAMPLE_STEP = 3

    /**
     * 计算扩展后的 inclusive 整数 AABB [x0, y0, x1, y1]。
     *
     * @param pxScale 底图相对参考分辨率的像素密度倍数
     */
    fun expand(bitmap: Bitmap, region: OcrTextRegion, bgColor: Int, pxScale: Float = 1f): IntArray {
        val maxExpand = (MAX_EXPAND_PX * pxScale).roundToInt().coerceAtLeast(1)
        val sampleStep = (SAMPLE_STEP * pxScale).roundToInt().coerceAtLeast(1)
        val W = bitmap.width
        val H = bitmap.height
        val xs = region.corners.map { it.first }
        val ys = region.corners.map { it.second }
        var x0 = xs.min().toInt().coerceIn(0, W - 1)
        var y0 = ys.min().toInt().coerceIn(0, H - 1)
        var x1 = xs.max().toInt().coerceIn(0, W - 1)
        var y1 = ys.max().toInt().coerceIn(0, H - 1)
        val ox0 = x0; val oy0 = y0; val ox1 = x1; val oy1 = y1

        // 一次性把 region + MAX_EXPAND 范围的像素抓进 IntArray,后续按数组采样
        // 避开 getPixel 单像素 JNI 反复调用,几百万次差异肉眼可感
        val wx0 = (x0 - maxExpand).coerceIn(0, W - 1)
        val wy0 = (y0 - maxExpand).coerceIn(0, H - 1)
        val wx1 = (x1 + maxExpand).coerceIn(0, W - 1)
        val wy1 = (y1 + maxExpand).coerceIn(0, H - 1)
        val ww = wx1 - wx0 + 1
        val wh = wy1 - wy0 + 1
        val buf = IntArray(ww * wh)
        bitmap.getPixels(buf, 0, ww, wx0, wy0, ww, wh)

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        fun isBg(x: Int, y: Int): Boolean {
            if (x < wx0 || x > wx1 || y < wy0 || y > wy1) return false
            val p = buf[(y - wy0) * ww + (x - wx0)]
            val dr = abs(Color.red(p) - bgR)
            val dg = abs(Color.green(p) - bgG)
            val db = abs(Color.blue(p) - bgB)
            return maxOf(dr, dg, db) < DIFF_THRESHOLD
        }
        fun rowOk(y: Int, fromX: Int, toX: Int): Boolean {
            if (y < 0 || y >= H) return false
            var bg = 0; var total = 0
            var x = fromX
            while (x <= toX) { if (isBg(x, y)) bg++; total++; x += sampleStep }
            return total > 0 && bg.toFloat() / total >= ROW_BG_RATIO
        }
        fun colOk(x: Int, fromY: Int, toY: Int): Boolean {
            if (x < 0 || x >= W) return false
            var bg = 0; var total = 0
            var y = fromY
            while (y <= toY) { if (isBg(x, y)) bg++; total++; y += sampleStep }
            return total > 0 && bg.toFloat() / total >= ROW_BG_RATIO
        }

        var s: Int
        s = 0; while (s < maxExpand && y0 > 0 && rowOk(y0 - 1, x0, x1)) { y0--; s++ }
        s = 0; while (s < maxExpand && y1 < H - 1 && rowOk(y1 + 1, x0, x1)) { y1++; s++ }
        s = 0; while (s < maxExpand && x0 > 0 && colOk(x0 - 1, y0, y1)) { x0--; s++ }
        s = 0; while (s < maxExpand && x1 < W - 1 && colOk(x1 + 1, y0, y1)) { x1++; s++ }

        Timber.d(
            "BubbleAreaFinder: AABB [%d,%d %dx%d] → [%d,%d %dx%d]",
            ox0, oy0, ox1 - ox0 + 1, oy1 - oy0 + 1,
            x0, y0, x1 - x0 + 1, y1 - y0 + 1,
        )
        return intArrayOf(x0, y0, x1, y1)
    }
}
