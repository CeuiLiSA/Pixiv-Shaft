package ceui.pixiv.witstudio.theme

import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.pow

/**
 * APCA（Accessible Perceptual Contrast Algorithm，WCAG 3 的候选对比度算法）的 Lc 计算。
 *
 * 存在的理由是 WCAG 2.x 的对比度公式**不区分极性**：同样的亮度比，"浅字压暗底"（深色模式）
 * 在感知上明显弱于"深字压浅底"。实测十档主题色的染色标签胶囊，深色模式下 WCAG 4.78 看着
 * 像刚好及格，换成 APCA 只有 |Lc| 43 —— 连"大号粗体"档（45）都没到。APCA 用不同指数
 * （反向 0.65/0.62、正向 0.56/0.57）把这件事算进去，所以标签辨识度以它为准。
 *
 * 实现对齐 APCA-W3 0.1.98G-4g。自检：lc(黑, 白) ≈ 106，lc(白, 黑) ≈ -107.9。
 */
public object ApcaContrast {

    /** 正文最低线。标签胶囊是 11.5–13sp 小字，属于正文尺寸，按这条收口。 */
    public const val BODY_TEXT_MIN: Double = 75.0

    private const val R_CO = 0.2126729
    private const val G_CO = 0.7151522
    private const val B_CO = 0.0721750
    private const val BLK_THRS = 0.022
    private const val BLK_CLMP = 1.414
    private const val NORM_BG = 0.56
    private const val NORM_TXT = 0.57
    private const val REV_BG = 0.65
    private const val REV_TXT = 0.62
    private const val SCALE = 1.14
    private const val OFFSET = 0.027
    private const val LO_CLIP = 0.1
    private const val DELTA_Y_MIN = 0.0005

    /** 屏幕相对亮度（sRGB 线性化后按 Rec.709 系数加权）。 */
    @JvmStatic
    public fun luminance(@ColorInt color: Int): Double {
        // 直接移位取通道，不依赖 android.graphics.Color —— 本对象是纯算法，保持零 Android
        // 依赖后单测能跑普通 JVM，不必为了它拉一整套 Robolectric。
        val r = linear((color shr 16) and 0xFF)
        val g = linear((color shr 8) and 0xFF)
        val b = linear(color and 0xFF)
        return R_CO * r + G_CO * g + B_CO * b
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** 低亮度段的软钳位，避免近黑区域的噪声被放大。 */
    private fun softClamp(y: Double): Double =
        if (y < BLK_THRS) y + (BLK_THRS - y).pow(BLK_CLMP) else y

    /**
     * 返回 Lc。**浅字压暗底是负值**（深色模式），深字压浅底是正值；比较时一律取绝对值。
     * 两端亮度差小于 [DELTA_Y_MIN] 时返回 0（视为不可分辨）。
     */
    @JvmStatic
    public fun lc(@ColorInt text: Int, @ColorInt background: Int): Double {
        val yt = softClamp(luminance(text))
        val yb = softClamp(luminance(background))
        if (abs(yb - yt) < DELTA_Y_MIN) return 0.0
        return if (yb > yt) {
            val s = (yb.pow(NORM_BG) - yt.pow(NORM_TXT)) * SCALE
            if (s < LO_CLIP) 0.0 else (s - OFFSET) * 100.0
        } else {
            val s = (yb.pow(REV_BG) - yt.pow(REV_TXT)) * SCALE
            if (s > -LO_CLIP) 0.0 else (s + OFFSET) * 100.0
        }
    }
}