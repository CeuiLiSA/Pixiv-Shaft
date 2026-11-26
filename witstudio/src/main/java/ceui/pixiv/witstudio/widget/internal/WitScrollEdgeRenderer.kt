package ceui.pixiv.witstudio.widget.internal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.math.ceil

/**
 * Hardware-accelerated live progressive blur renderer for
 * [ceui.pixiv.witstudio.widget.WitScrollEdgeBlurView].
 *
 * - **API 31+ (`STANDARD`)**: [WitScrollEdgeStandardRenderer] renders a single downscaled HWUI
 *   Gaussian blur RenderNode feathered through an eased multi-stop `DST_IN` alpha mask and topped
 *   by the matching soft veil gradient.
 * - **API 33+ (`ULTRA`)**: [WitScrollEdgeUltraRenderer] renders a true 4-level geometric hardware
 *   Gaussian pyramid (`2.0 / 4.8 / 10.0 / 18.0 dp`) composited via AGSL [RuntimeShader] with exact
 *   piecewise trilinear pyramid weights (zero sharp-over-blur ghosting), iOS 26 vibrancy
 *   preservation, luminance-adaptive veil, and Interleaved Gradient Noise dithering to eliminate
 *   8-bit OLED banding.
 */
@RequiresApi(31)
internal abstract class WitScrollEdgeRenderer {
    internal abstract val backend: WitLiquidGlassBackend
    internal abstract val hasBackdrop: Boolean

    internal abstract fun updateBackdrop(
        view: View,
        backdrop: WitLiquidGlassBackdrop,
        parentScrollX: Int,
        parentScrollY: Int,
        density: Float,
        canvasEdge: Int,
        maxBlurDp: Float,
        isTopEdge: Boolean,
    ): Boolean

    internal abstract fun updateOptics(
        width: Int,
        height: Int,
        anchorHeightPx: Float,
        featherHeightPx: Float,
        density: Float,
        isDark: Boolean,
        isTopEdge: Boolean,
        @ColorInt scrimColor: Int,
        scrimStrength: Float,
    )

    internal abstract fun draw(canvas: Canvas, width: Int, height: Int)

    internal abstract fun clearBackdrop(): Boolean
}

/**
 * Single-pass HWUI blur + eased vertical alpha mask (`STANDARD` quality and API 31-32 devices).
 */
@RequiresApi(31)
internal class WitScrollEdgeStandardRenderer : WitScrollEdgeRenderer() {
    override val backend: WitLiquidGlassBackend = WitLiquidGlassBackend.BACKDROP

    private val node = RenderNode("WitScrollEdgeStd")
    private val localToBackdrop = Matrix()
    private val backdropToLocal = Matrix()
    private val previousTransform = Matrix()
    private var previousBackdrop: WitLiquidGlassBackdrop? = null
    private var renderScale = 1f
    private var topPadPx = 0
    private var blurRadiusPx = -1f
    private var allocatedW = 0
    private var allocatedH = 0

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var maskShader: LinearGradient? = null
    private var veilShader: LinearGradient? = null
    private var activeSpanH = 0f
    private var activeIsTop = true

    override val hasBackdrop: Boolean
        get() = previousBackdrop != null && node.hasDisplayList()

    override fun updateBackdrop(
        view: View,
        backdrop: WitLiquidGlassBackdrop,
        parentScrollX: Int,
        parentScrollY: Int,
        density: Float,
        canvasEdge: Int,
        maxBlurDp: Float,
        isTopEdge: Boolean,
    ): Boolean {
        val w = view.width
        val h = view.height
        val plan = WitScrollEdgeCurve.planPyramid(w, h, density, canvasEdge, ultra = false, maxBlurDp = maxBlurDp)
        val spec = plan.levels.firstOrNull()
        if (spec == null) {
            clearBackdrop()
            return false
        }

        localToBackdrop.setTranslate((view.left - parentScrollX).toFloat(), (view.top - parentScrollY).toFloat())
        localToBackdrop.preConcat(view.matrix)
        if (!localToBackdrop.invert(backdropToLocal)) {
            clearBackdrop()
            return false
        }

        val hostH = backdrop.node.height
        val viewTopInHost = (view.top - parentScrollY).coerceAtLeast(0)
        val viewBottomInHost = (view.bottom - parentScrollY).coerceAtLeast(0)
        val clampedTopPad = minOf(spec.padPx, viewTopInHost).coerceAtLeast(0)
        val clampedBottomPad = minOf(spec.padPx, (hostH - viewBottomInHost).coerceAtLeast(0)).coerceAtLeast(0)

        val scale = spec.scale
        val sw = ceil(w * scale).toInt().coerceAtLeast(1)
        val sh = ceil((h + clampedTopPad + clampedBottomPad) * scale).toInt().coerceAtLeast(1)
        val resized = allocatedW != sw || allocatedH != sh
        val padChanged = topPadPx != clampedTopPad
        val scaleChanged = renderScale != scale
        val missingList = !node.hasDisplayList()
        val backdropChanged = previousBackdrop !== backdrop

        val targetBlurPx = (spec.sigmaDp * density * scale).coerceAtLeast(0.5f)
        if (blurRadiusPx != targetBlurPx) {
            node.setRenderEffect(RenderEffect.createBlurEffect(targetBlurPx, targetBlurPx, Shader.TileMode.CLAMP))
            blurRadiusPx = targetBlurPx
        }

        if (!resized && !padChanged && !scaleChanged && !missingList && !backdropChanged &&
            previousTransform == backdropToLocal
        ) {
            return false
        }

        if (resized) {
            allocatedW = sw
            allocatedH = sh
            node.setPosition(0, 0, sw, sh)
        }
        topPadPx = clampedTopPad
        renderScale = scale

        val recording = node.beginRecording()
        try {
            if (clampedTopPad > 0) {
                recording.translate(0f, clampedTopPad * scale)
            }
            if (scale != 1f) {
                recording.scale(scale, scale)
            }
            recording.concat(backdropToLocal)
            recording.drawRenderNode(backdrop.node)
        } finally {
            node.endRecording()
        }

        previousBackdrop = backdrop
        previousTransform.set(backdropToLocal)
        return resized || padChanged || scaleChanged || missingList || backdropChanged
    }

    override fun updateOptics(
        width: Int,
        height: Int,
        anchorHeightPx: Float,
        featherHeightPx: Float,
        density: Float,
        isDark: Boolean,
        isTopEdge: Boolean,
        @ColorInt scrimColor: Int,
        scrimStrength: Float,
    ) {
        if (width <= 0 || height <= 0) {
            maskShader = null
            veilShader = null
            activeSpanH = 0f
            return
        }
        val spanH = (anchorHeightPx + featherHeightPx).coerceIn(1f, height.toFloat())
        activeSpanH = spanH
        activeIsTop = isTopEdge
        val y0 = if (isTopEdge) 0f else height.toFloat()
        val y1 = if (isTopEdge) spanH else height.toFloat() - spanH

        val (maskPos, maskAlphas) = WitScrollEdgeCurve.buildStandardBlurMaskStops(anchorHeightPx, featherHeightPx)
        val maskColors = IntArray(maskAlphas.size) { i -> Color.argb(maskAlphas[i], 255, 255, 255) }
        maskShader = LinearGradient(0f, y0, 0f, y1, maskColors, maskPos, Shader.TileMode.CLAMP)

        val (veilPos, veilAlphas) = WitScrollEdgeCurve.buildStandardVeilStops(
            anchorHeightPx, featherHeightPx, isDark, scrimStrength,
        )
        val opaqueScrim = scrimColor or Color.BLACK
        val veilColors = IntArray(veilAlphas.size) { i ->
            ColorUtils.setAlphaComponent(opaqueScrim, veilAlphas[i])
        }
        veilShader = LinearGradient(0f, y0, 0f, y1, veilColors, veilPos, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !hasBackdrop) return
        val mask = maskShader ?: return
        val wF = width.toFloat()
        val hF = height.toFloat()
        val spanH = activeSpanH.coerceIn(0f, hF)
        if (spanH <= 0.5f) return
        val drawTop = if (activeIsTop) 0f else hF - spanH
        val drawBottom = if (activeIsTop) spanH else hF

        val layer = canvas.saveLayer(0f, drawTop, wF, drawBottom, null)
        try {
            canvas.withClip(0f, drawTop, wF, drawBottom) {
                val inv = 1f / renderScale
                withTranslation(0f, -topPadPx.toFloat()) {
                    withScale(inv, inv) {
                        drawRenderNode(node)
                    }
                }
            }
            maskPaint.shader = mask
            canvas.drawRect(0f, drawTop, wF, drawBottom, maskPaint)
            maskPaint.shader = null
        } finally {
            canvas.restoreToCount(layer)
        }

        val veil = veilShader
        if (veil != null) {
            veilPaint.shader = veil
            canvas.drawRect(0f, drawTop, wF, drawBottom, veilPaint)
            veilPaint.shader = null
        }
    }

    override fun clearBackdrop(): Boolean {
        val had = previousBackdrop != null
        previousBackdrop = null
        allocatedW = 0
        allocatedH = 0
        blurRadiusPx = -1f
        node.setRenderEffect(null)
        node.discardDisplayList()
        return had
    }
}

/**
 * True 4-level hardware Gaussian pyramid + AGSL per-pixel continuous variable-radius progressive
 * blur renderer (API 33+ `ULTRA` quality).
 *
 * Calibrated directly against iOS 26 `UIScrollEdgeEffect.Style.soft`:
 * - Zero sharp-over-blur double-vision (each pyramid level reaches alpha=1.0 before the next begins).
 * - Zero seam at bottom feather edge (C² smootherstep zero-derivative landing).
 * - Subtle vibrancy preservation & luminance-adaptive surface veil (protects status bar & floating
 *   header contrast over both pure-white and pure-black illustrations while keeping rich colors glowing).
 * - Interleaved Gradient Noise micro-dithering to eliminate OLED 8-bit gradient banding.
 */
@RequiresApi(33)
internal class WitScrollEdgeUltraRenderer : WitScrollEdgeRenderer() {
    override val backend: WitLiquidGlassBackend = WitLiquidGlassBackend.REFRACTION

    private class PyramidLevel(val index: Int) {
        val node = RenderNode("WitScrollEdgeL$index")
        val shader = RuntimeShader(SHADER_SOURCE)
        var blurEffect: RenderEffect? = null
        var blurRadiusPx = -1f
        var scale = 1f
        var topPadPx = 0
        var allocatedW = 0
        var allocatedH = 0
        var active = false

        fun clear() {
            active = false
            allocatedW = 0
            allocatedH = 0
            blurRadiusPx = -1f
            blurEffect = null
            node.setRenderEffect(null)
            node.discardDisplayList()
        }
    }

    private val levels = Array(WitScrollEdgeCurve.PYRAMID_LEVELS) { PyramidLevel(it) }
    private val localToBackdrop = Matrix()
    private val backdropToLocal = Matrix()
    private val previousTransform = Matrix()
    private var previousBackdrop: WitLiquidGlassBackdrop? = null
    private var previousMaxBlurDp = -1f
    private var previousIsTopEdge = true
    private var activeSpanH = 0f
    private var activeIsTop = true

    override val hasBackdrop: Boolean
        get() = previousBackdrop != null && levels[0].active && levels[0].node.hasDisplayList()

    override fun updateBackdrop(
        view: View,
        backdrop: WitLiquidGlassBackdrop,
        parentScrollX: Int,
        parentScrollY: Int,
        density: Float,
        canvasEdge: Int,
        maxBlurDp: Float,
        isTopEdge: Boolean,
    ): Boolean {
        val w = view.width
        val h = view.height
        val plan = WitScrollEdgeCurve.planPyramid(w, h, density, canvasEdge, ultra = true, maxBlurDp = maxBlurDp)
        if (plan.levels.size != levels.size) {
            clearBackdrop()
            return false
        }

        localToBackdrop.setTranslate((view.left - parentScrollX).toFloat(), (view.top - parentScrollY).toFloat())
        localToBackdrop.preConcat(view.matrix)
        if (!localToBackdrop.invert(backdropToLocal)) {
            clearBackdrop()
            return false
        }

        val hostH = backdrop.node.height
        val viewTopInHost = (view.top - parentScrollY).coerceAtLeast(0)
        val viewBottomInHost = (view.bottom - parentScrollY).coerceAtLeast(0)
        val availableBelow = (hostH - viewBottomInHost).coerceAtLeast(0)
        val backdropChanged = previousBackdrop !== backdrop
        val transformChanged = previousTransform != backdropToLocal
        val blurParamChanged = previousMaxBlurDp != maxBlurDp || previousIsTopEdge != isTopEdge
        var anyOpticsInvalidated = backdropChanged || blurParamChanged

        for (i in levels.indices) {
            val level = levels[i]
            val spec = plan.levels[i]
            val clampedTopPad = minOf(spec.padPx, viewTopInHost).coerceAtLeast(0)
            val clampedBottomPad = minOf(spec.padPx, availableBelow).coerceAtLeast(0)
            val scale = spec.scale
            val sw = ceil(w * scale).toInt().coerceAtLeast(1)
            val sh = ceil((h + clampedTopPad + clampedBottomPad) * scale).toInt().coerceAtLeast(1)

            val resized = level.allocatedW != sw || level.allocatedH != sh
            val padChanged = level.topPadPx != clampedTopPad
            val scaleChanged = level.scale != scale
            val missingList = !level.node.hasDisplayList()

            val targetBlurPx = (spec.sigmaDp * density * scale).coerceAtLeast(0.5f)
            if (level.blurEffect == null || level.blurRadiusPx != targetBlurPx) {
                level.blurEffect = RenderEffect.createBlurEffect(targetBlurPx, targetBlurPx, Shader.TileMode.CLAMP)
                level.blurRadiusPx = targetBlurPx
                anyOpticsInvalidated = true
            }

            if (resized || padChanged || scaleChanged || missingList || backdropChanged || transformChanged) {
                if (resized) {
                    level.allocatedW = sw
                    level.allocatedH = sh
                    level.node.setPosition(0, 0, sw, sh)
                }
                level.topPadPx = clampedTopPad
                level.scale = scale
                val recording = level.node.beginRecording()
                try {
                    if (clampedTopPad > 0) {
                        recording.translate(0f, clampedTopPad * scale)
                    }
                    if (scale != 1f) {
                        recording.scale(scale, scale)
                    }
                    recording.concat(backdropToLocal)
                    recording.drawRenderNode(backdrop.node)
                } finally {
                    level.node.endRecording()
                }
                if (resized || padChanged || scaleChanged || missingList) {
                    anyOpticsInvalidated = true
                }
            }
            level.active = true
        }

        previousBackdrop = backdrop
        previousMaxBlurDp = maxBlurDp
        previousIsTopEdge = isTopEdge
        previousTransform.set(backdropToLocal)
        return anyOpticsInvalidated
    }

    override fun updateOptics(
        width: Int,
        height: Int,
        anchorHeightPx: Float,
        featherHeightPx: Float,
        density: Float,
        isDark: Boolean,
        isTopEdge: Boolean,
        @ColorInt scrimColor: Int,
        scrimStrength: Float,
    ) {
        if (width <= 0 || height <= 0 || !hasBackdrop) return
        val totalH = height.toFloat().coerceAtLeast(1f)
        val safeFeather = featherHeightPx.coerceIn(1f, totalH)
        val safeAnchor = anchorHeightPx.coerceIn(0f, (totalH - safeFeather).coerceAtLeast(0f))
        val spanH = (safeAnchor + safeFeather).coerceIn(1f, totalH)
        activeSpanH = spanH
        activeIsTop = isTopEdge
        val opaqueScrim = scrimColor or Color.BLACK
        val darkFlag = if (isDark) 1f else 0f
        val topFlag = if (isTopEdge) 1f else 0f
        val clampedStrength = scrimStrength.coerceIn(0f, 1f)

        for (i in levels.indices) {
            val level = levels[i]
            val blur = level.blurEffect ?: continue
            val rLo = if (i == 0) 0f else WitScrollEdgeCurve.PYRAMID_RADIUS_RATIOS[i - 1]
            val rHi = WitScrollEdgeCurve.PYRAMID_RADIUS_RATIOS[i]
            level.shader.setFloatUniform(
                "metrics",
                totalH,
                spanH,
                level.topPadPx.toFloat(),
                topFlag,
            )
            level.shader.setFloatUniform(
                "layer",
                level.scale,
                rLo,
                rHi,
                WitScrollEdgeCurve.BLUR_CURVE_EXPONENT,
            )
            level.shader.setFloatUniform(
                "tone",
                darkFlag,
                clampedStrength,
                if (isDark) WitScrollEdgeCurve.LIVE_VEIL_MAX_ALPHA_DARK else WitScrollEdgeCurve.LIVE_VEIL_MAX_ALPHA_LIGHT,
                if (isDark) WitScrollEdgeCurve.VIBRANCY_SATURATION_DARK else WitScrollEdgeCurve.VIBRANCY_SATURATION_LIGHT,
            )
            level.shader.setColorUniform("scrimColor", opaqueScrim)
            val maskEffect = RenderEffect.createRuntimeShaderEffect(level.shader, "backdrop")
            level.node.setRenderEffect(RenderEffect.createChainEffect(maskEffect, blur))
        }
    }

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !hasBackdrop) return
        val hF = height.toFloat()
        val spanH = activeSpanH.coerceIn(0f, hF)
        if (spanH <= 0.5f) return
        val drawTop = if (activeIsTop) 0f else hF - spanH
        val drawBottom = if (activeIsTop) spanH else hF
        canvas.withClip(0f, drawTop, width.toFloat(), drawBottom) {
            for (i in levels.indices) {
                val level = levels[i]
                if (!level.active || !level.node.hasDisplayList()) continue
                val inv = 1f / level.scale
                withTranslation(0f, -level.topPadPx.toFloat()) {
                    withScale(inv, inv) {
                        drawRenderNode(level.node)
                    }
                }
            }
        }
    }

    override fun clearBackdrop(): Boolean {
        val had = previousBackdrop != null
        previousBackdrop = null
        previousMaxBlurDp = -1f
        activeSpanH = 0f
        for (level in levels) {
            level.clear()
        }
        return had
    }

    private companion object {
        private const val SHADER_SOURCE = """
            uniform shader backdrop;
            uniform float4 metrics; // totalH, spanH (anchorH+featherH), topPadPx, isTopEdge (1=top, 0=bottom)
            uniform float4 layer;   // renderScale, rLo (0..1), rHi (0..1), blurExponent
            uniform float4 tone;    // isDark, scrimStrength, maxVeilAlpha, vibrancySat
            layout(color) uniform half4 scrimColor;

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

            // Interleaved Gradient Noise (Jimenez 2014) for +/-0.5 LSB OLED anti-banding dithering.
            float ignNoise(float2 pos) {
                return fract(52.9829189 * fract(dot(pos, float2(0.06711056, 0.00583715))));
            }

            half4 main(float2 coord) {
                float scale = max(layer.x, 0.01);
                float y = coord.y / scale - metrics.z;
                float totalH = metrics.x;
                if (y < 0.0 || y >= totalH) {
                    return half4(0.0);
                }

                float distFromEdge = mix(totalH - y, y, metrics.w);
                float spanH = max(metrics.y, 1.0);
                if (distFromEdge >= spanH) {
                    return half4(0.0);
                }

                // 1) Inflection-free continuous power curve across the entire spanH (zero shelf, zero cliff).
                float u = clamp((spanH - distFromEdge) / spanH, 0.0, 1.0);
                float blurNorm = u > 0.0 ? pow(u, layer.w) : 0.0;

                // Exact physical-radius trilinear pyramid weight (zero sharp-over-blur ghosting).
                float rSpan = max(layer.z - layer.y, 0.0001);
                float alpha = clamp((blurNorm - layer.y) / rSpan, 0.0, 1.0);
                if (alpha <= 0.001) {
                    return half4(0.0);
                }

                // 2) Sample blurred scene and composite over ground if backdrop has transparency.
                half4 px = backdrop.eval(coord);
                float3 ground = float3(scrimColor.rgb);
                float3 scene = float3(px.rgb) + ground * (1.0 - float(px.a));

                // 3) Subtle Apple-style vibrancy preservation ramping smoothly with u.
                float luma = dot(scene, LUMA);
                float sat = mix(1.0, tone.w, u);
                float3 vibrant = clamp(mix(float3(luma), scene, sat), 0.0, 1.0);

                // 4) Smooth inflection-free atmospheric surface veil + luminance-adaptive protection.
                float scrimNorm = u > 0.0 ? pow(u, 1.70) : 0.0;
                float isDark = tone.x;
                float contrastRisk = mix(1.0 - smoothstep(0.06, 0.42, luma), smoothstep(0.45, 0.90, luma), isDark);
                float maxVeil = clamp(tone.z + 0.14 * contrastRisk, 0.0, 0.94);
                float veilAlpha = clamp(scrimNorm * maxVeil * tone.y, 0.0, 0.96);

                float3 color = mix(vibrant, ground, veilAlpha);

                // Subtle +/-0.5 LSB dither eliminates 8-bit horizontal banding on OLED displays.
                float dither = (ignNoise(coord) - 0.5) / 255.0;
                color = clamp(color + dither * alpha, 0.0, 1.0);

                return half4(half3(color * alpha), half(alpha));
            }
        """
    }
}
