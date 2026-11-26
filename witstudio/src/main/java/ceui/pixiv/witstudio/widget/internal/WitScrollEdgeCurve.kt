package ceui.pixiv.witstudio.widget.internal

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure optical curves, 5-level hardware Gaussian pyramid planner, and fallback gradient builder
 * for [ceui.pixiv.witstudio.widget.WitScrollEdgeBlurView] (pixel-calibrated against iOS 26
 * `UIScrollEdgeEffect.Style.soft`).
 *
 * Uses a single inflection-free power curve across the entire `[0, anchorH + featherH]` span
 * mapped directly onto exact physical Gaussian radii (`0 -> 1.0 -> 2.6 -> 5.8 -> 11.2 -> 18.0 dp`),
 * guaranteeing zero shelf, zero mid-band cliff, and zero sharp-over-blur ghosting.
 */
internal object WitScrollEdgeCurve {

    internal const val DEFAULT_FEATHER_DP: Float = 24f
    internal const val DEFAULT_MAX_BLUR_DP: Float = 18f
    internal const val MIN_BLUR_DP: Float = 2f
    internal const val MAX_BLUR_DP: Float = 32f
    internal const val STANDARD_BLUR_RATIO: Float = 0.62f

    internal const val BLUR_CURVE_EXPONENT: Float = 1.85f
    internal const val SCRIM_CURVE_EXPONENT: Float = 1.70f

    internal const val PYRAMID_LEVELS: Int = 5
    private const val MAX_NODE_EDGE: Int = 2_048
    private const val MAX_PYRAMID_PIXELS: Long = 1_200_000L
    private const val MIN_RENDER_SCALE: Float = 0.14f

    /**
     * 5-level geometric progression of physical Gaussian blur radii (normalized to
     * [DEFAULT_MAX_BLUR_DP] = 18dp):
     * - L1 = 1.0dp (0.0556x): gossamer micro-softening eliminating any sharp-to-blur seam
     * - L2 = 2.6dp (0.1444x): gentle soft focus (2.60x ratio)
     * - L3 = 5.8dp (0.3222x): smooth mid-transition blur (2.23x ratio)
     * - L4 = 11.2dp (0.6222x): heavy header/nav-bar bokeh (1.93x ratio)
     * - L5 = 18.0dp (1.0000x): lush deep status-bar blur at screen edge (1.61x ratio)
     */
    internal val PYRAMID_RADIUS_RATIOS: FloatArray = floatArrayOf(
        1.0f / 18.0f,
        2.6f / 18.0f,
        5.8f / 18.0f,
        11.2f / 18.0f,
        1.0f,
    )

    /**
     * Nyquist-safe downscale factors per pyramid level. Every level retains >= 2.16 scaled-px
     * Gaussian sigma at 3x density, keeping total offscreen pixels around ~500K on 1080p displays.
     */
    internal val PYRAMID_BASE_SCALES: FloatArray = floatArrayOf(0.72f, 0.54f, 0.38f, 0.26f, 0.20f)

    internal const val STANDARD_BASE_SCALE: Float = 0.45f

    /** Peak soft-veil alpha at the screen edge (y = 0) when live blur is active. */
    internal const val LIVE_VEIL_MAX_ALPHA_DARK: Float = 0.72f
    internal const val LIVE_VEIL_MAX_ALPHA_LIGHT: Float = 0.78f

    /** Subtle iOS CABackdropLayer saturation boost preventing chalky/muddy washes. */
    internal const val VIBRANCY_SATURATION_DARK: Float = 1.08f
    internal const val VIBRANCY_SATURATION_LIGHT: Float = 1.12f

    internal data class PyramidLevelPlan(
        val levelIndex: Int,
        val sigmaDp: Float,
        val scale: Float,
        val padPx: Int,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val pixels: Long,
    )

    internal data class PyramidPlan(
        val levels: List<PyramidLevelPlan>,
        val totalPixels: Long,
    )

    /** C² quintic smootherstep: zero 1st and 2nd derivatives at both endpoints [0, 1]. */
    internal fun smootherstep01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    /**
     * Normalized target Gaussian blur radius `sigma(y) / maxBlur` in `[0, 1]` at `distFromEdge`
     * from the active screen edge (`0` = screen edge, `anchorH + featherH` = clear boundary).
     *
     * Uses a single smooth convex curve `u^1.85` across the entire height `anchorH + featherH`
     * with ZERO inflection point, ZERO plateau shelf, and ZERO abrupt transition cliff.
     */
    internal fun evaluateBlurNorm(distFromEdge: Float, anchorH: Float, featherH: Float): Float {
        val totalH = (anchorH.coerceAtLeast(0f) + featherH.coerceAtLeast(1f)).coerceAtLeast(1f)
        if (distFromEdge >= totalH) return 0f
        if (distFromEdge <= 0f) return 1f
        val u = ((totalH - distFromEdge) / totalH).coerceIn(0f, 1f)
        return u.pow(BLUR_CURVE_EXPONENT)
    }

    /**
     * Continuous soft-veil intensity in `[0, 1]` at `distFromEdge` from the active screen edge.
     *
     * Follows an equally inflection-free continuous curve `u^1.70` across the entire span so the
     * atmospheric surface tint dissolves invisibly into the sharp content with zero band edge.
     */
    internal fun evaluateScrimNorm(distFromEdge: Float, anchorH: Float, featherH: Float): Float {
        val totalH = (anchorH.coerceAtLeast(0f) + featherH.coerceAtLeast(1f)).coerceAtLeast(1f)
        if (distFromEdge >= totalH) return 0f
        if (distFromEdge <= 0f) return 1f
        val u = ((totalH - distFromEdge) / totalH).coerceIn(0f, 1f)
        return u.pow(SCRIM_CURVE_EXPONENT)
    }

    /**
     * Exact SRC_OVER compositing alpha for pyramid level [layerIndex] (`0..PYRAMID_LEVELS-1`)
     * drawn in ascending order (`L1..L5`) over the sharp scene `L0`.
     *
     * Interpolates directly in **physical radius space** (`[rLo, rHi]`) so the effective screen
     * Gaussian radius strictly matches `blurNorm * maxBlurDp` with ZERO double-vision ghosting.
     */
    internal fun evaluateLayerAlpha(
        blurNorm: Float,
        layerIndex: Int,
        levelCount: Int = PYRAMID_LEVELS,
    ): Float {
        if (levelCount <= 0 || layerIndex !in 0 until PYRAMID_RADIUS_RATIOS.size) return 0f
        val r = blurNorm.coerceIn(0f, 1f)
        val rLo = if (layerIndex == 0) 0f else PYRAMID_RADIUS_RATIOS[layerIndex - 1]
        val rHi = PYRAMID_RADIUS_RATIOS[layerIndex]
        val span = (rHi - rLo).coerceAtLeast(1e-5f)
        return ((r - rLo) / span).coerceIn(0f, 1f)
    }

    /**
     * Maps [blurNorm] (`0..1`) directly to the physical effective Gaussian sigma (dp).
     */
    internal fun evaluateEffectiveSigmaDp(
        blurNorm: Float,
        maxBlurDp: Float = DEFAULT_MAX_BLUR_DP,
    ): Float = blurNorm.coerceIn(0f, 1f) * maxBlurDp.coerceAtLeast(0f)

    /**
     * Plans the offscreen RenderNode dimensions & scales for either ULTRA (5-level pyramid) or
     * STANDARD (1-level blur) while strictly honoring [canvasEdge] and [MAX_PYRAMID_PIXELS].
     */
    internal fun planPyramid(
        width: Int,
        height: Int,
        density: Float,
        canvasEdge: Int,
        ultra: Boolean,
        maxBlurDp: Float = DEFAULT_MAX_BLUR_DP,
    ): PyramidPlan {
        if (width <= 0 || height <= 0 || canvasEdge <= 0) {
            return PyramidPlan(emptyList(), 0L)
        }
        val safeDensity = density.coerceAtLeast(0.5f)
        val clampedBlurDp = maxBlurDp.coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        val edgeLimit = min(MAX_NODE_EDGE, canvasEdge)

        val levelSpecs = if (ultra) {
            List(PYRAMID_LEVELS) { i ->
                (PYRAMID_RADIUS_RATIOS[i] * clampedBlurDp) to PYRAMID_BASE_SCALES[i]
            }
        } else {
            listOf((clampedBlurDp * STANDARD_BLUR_RATIO) to STANDARD_BASE_SCALE)
        }

        var globalScaleFactor = 1f
        repeat(4) {
            val planned = ArrayList<PyramidLevelPlan>(levelSpecs.size)
            var totalPixels = 0L
            var valid = true
            for (i in levelSpecs.indices) {
                val (sigmaDp, baseScale) = levelSpecs[i]
                val padPx = ceil(sigmaDp * 2.5f * safeDensity).toInt().coerceAtLeast(2)
                val paddedH = height + padPx

                var scale = (baseScale * globalScaleFactor).coerceIn(MIN_RENDER_SCALE, 1f)
                if (width * scale > edgeLimit) {
                    scale = edgeLimit.toFloat() / width.toFloat()
                }
                if (paddedH * scale > edgeLimit) {
                    scale = min(scale, edgeLimit.toFloat() / paddedH.toFloat())
                }
                if (scale < MIN_RENDER_SCALE) {
                    valid = false
                    break
                }
                val sw = ceil(width * scale).toInt().coerceAtLeast(1)
                val sh = ceil(paddedH * scale).toInt().coerceAtLeast(1)
                if (sw > edgeLimit || sh > edgeLimit) {
                    valid = false
                    break
                }
                val px = sw.toLong() * sh.toLong()
                totalPixels += px
                planned += PyramidLevelPlan(
                    levelIndex = i,
                    sigmaDp = sigmaDp,
                    scale = scale,
                    padPx = padPx,
                    scaledWidth = sw,
                    scaledHeight = sh,
                    pixels = px,
                )
            }
            if (valid && totalPixels in 1L..MAX_PYRAMID_PIXELS) {
                return PyramidPlan(planned, totalPixels)
            }
            if (!valid) {
                return PyramidPlan(emptyList(), 0L)
            }
            globalScaleFactor *= 0.82f
        }
        return PyramidPlan(emptyList(), 0L)
    }

    /** Multi-stop gradient for `NONE` / API < 31 / software canvas fallback. */
    internal fun buildFallbackAlphaStops(
        anchorH: Float,
        featherH: Float,
        isDark: Boolean,
        opaqueHeaderFallback: Boolean,
        scrimStrength: Float,
        sampleCount: Int = 12,
    ): Pair<FloatArray, IntArray> {
        val safeAnchor = anchorH.coerceAtLeast(0f)
        val safeFeather = featherH.coerceAtLeast(1f)
        val totalH = safeAnchor + safeFeather
        val n = sampleCount.coerceAtLeast(3)
        val positions = FloatArray(n)
        val alphas = IntArray(n)
        val strength = scrimStrength.coerceIn(0f, 1f)

        for (i in 0 until n) {
            val frac = i.toFloat() / (n - 1).toFloat()
            val dist = frac * totalH
            positions[i] = frac
            val norm = if (opaqueHeaderFallback) {
                val tailStart = safeAnchor * 0.88f
                if (dist <= tailStart) {
                    1f
                } else {
                    val t = ((totalH - dist) / max(totalH - tailStart, 1f)).coerceIn(0f, 1f)
                    t.pow(1.65f)
                }
            } else {
                val maxAlpha = if (isDark) 0.86f else 0.90f
                evaluateScrimNorm(dist, safeAnchor, safeFeather) * maxAlpha
            }
            val a = if (opaqueHeaderFallback) norm * 0.98f * strength else norm * strength
            alphas[i] = (a * 255f).roundToInt().coerceIn(0, 255)
        }
        return positions to alphas
    }

    /** Multi-stop `DST_IN` mask for `STANDARD` single-pass hardware blur. */
    internal fun buildStandardBlurMaskStops(
        anchorH: Float,
        featherH: Float,
        sampleCount: Int = 12,
    ): Pair<FloatArray, IntArray> {
        val safeAnchor = anchorH.coerceAtLeast(0f)
        val safeFeather = featherH.coerceAtLeast(1f)
        val totalH = safeAnchor + safeFeather
        val n = sampleCount.coerceAtLeast(3)
        val positions = FloatArray(n)
        val alphas = IntArray(n)
        for (i in 0 until n) {
            val frac = i.toFloat() / (n - 1).toFloat()
            val dist = frac * totalH
            positions[i] = frac
            val u = ((totalH - dist) / totalH).coerceIn(0f, 1f)
            val mask = u.pow(1.60f)
            alphas[i] = (mask * 255f).roundToInt().coerceIn(0, 255)
        }
        return positions to alphas
    }

    /** Multi-stop live veil overlay for `STANDARD` mode (matching ULTRA's scrim curve). */
    internal fun buildStandardVeilStops(
        anchorH: Float,
        featherH: Float,
        isDark: Boolean,
        scrimStrength: Float,
        sampleCount: Int = 12,
    ): Pair<FloatArray, IntArray> {
        val safeAnchor = anchorH.coerceAtLeast(0f)
        val safeFeather = featherH.coerceAtLeast(1f)
        val totalH = safeAnchor + safeFeather
        val n = sampleCount.coerceAtLeast(3)
        val positions = FloatArray(n)
        val alphas = IntArray(n)
        val peak = (if (isDark) LIVE_VEIL_MAX_ALPHA_DARK else LIVE_VEIL_MAX_ALPHA_LIGHT) *
            scrimStrength.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val frac = i.toFloat() / (n - 1).toFloat()
            val dist = frac * totalH
            positions[i] = frac
            val veil = evaluateScrimNorm(dist, safeAnchor, safeFeather) * peak
            alphas[i] = (veil * 255f).roundToInt().coerceIn(0, 255)
        }
        return positions to alphas
    }
}
