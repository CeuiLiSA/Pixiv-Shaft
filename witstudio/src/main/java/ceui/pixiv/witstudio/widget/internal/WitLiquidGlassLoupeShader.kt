package ceui.pixiv.witstudio.widget.internal

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Hardware-accelerated AGSL liquid-glass droplet loupe renderer (API 33+).
 *
 * Combines Apple iOS 26 UITabBar's signature inward outer-bar meniscus shrink (`╰____╯` / `╭────╮`)
 * with crystal-clear 5-band Cauchy spectral dispersion and 3D floating droplet lighting:
 * - **Two-plane recording**: Plane 1 (1.0× razor-sharp live backdrop + **unscaled 1.0×** crystal
 *   outer glass bar platter + fading resting pill) followed by Plane 2 (`1.14×` crisp
 *   vector-magnified selected tab icons/labels).
 * - **C¹ convex-dome meniscus recess (`+normal * amp * pow(sin(u * π/2), 0.72) * (1 - smoothstep(...))`)**:
 *   subtly shrinks and curves the outer glass bar inward on top, bottom, AND left/right
 *   semicircular caps (`~4.4%` of bar height) while leaving crystal-clear protruding collars
 *   above/below the recessed bar and a flat distortion-free interior core.
 * - **Continuous 5-band Cauchy RGB spectral dispersion**: silky Red -> Yellow -> Green -> Cyan ->
 *   Blue prism splitting concentrated at the 4 curved bar-entry corner junctions (`╰`, `╯`, `╭`, `╮`)
 *   and curved meniscus shoulders, with zero distortion or color fringing in the center.
 * - **Crisp top/bottom specular rim, dark vertical optical silhouette & soft 3D floating shadow**:
 *   delineates the elevated water droplet cleanly over both light and dark bars.
 */
@RequiresApi(33)
internal class WitLiquidGlassLoupeShader {
    private val node = RenderNode("WitLiquidGlassTabLoupe")
    private val shader = RuntimeShader(SOURCE)
    private var allocatedNodeW = 0
    private var allocatedNodeH = 0
    private var hasCachedUniforms = false
    private var uDropCx = 0f
    private var uDropCy = 0f
    private var uHalfW = 0f
    private var uHalfH = 0f
    private var uBarL = 0f
    private var uBarT = 0f
    private var uBarR = 0f
    private var uBarB = 0f
    private var uSceneL = 0f
    private var uSceneT = 0f
    private var uSceneR = 0f
    private var uSceneB = 0f
    private var uVelStretch = 0f
    private var uGlass = 0f
    private var uDensity = 0f
    private var uIsDark = 0f

    internal fun draw(
        canvas: Canvas,
        barWidth: Int,
        barHeight: Int,
        density: Float,
        dropCx: Float,
        dropCy: Float,
        dropW: Float,
        dropH: Float,
        maxDropW: Float,
        maxDropH: Float,
        lift: Float,
        velocityStretch: Float,
        isDark: Boolean,
        sceneBoundsLocal: RectF?,
        recordContent: (Canvas) -> Unit,
    ): Boolean {
        if (barWidth <= 0 || barHeight <= 0 || dropW <= 1f || dropH <= 1f) return false
        val maxCanvasEdge = minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight, MAX_EDGE)
        if (maxCanvasEdge <= 0) return false

        // Tight pixel-snapped sliding window around the droplet (~2.7x smaller than the full bar),
        // bucketed to a stable 64px high-water envelope so HWUI never reallocates the backing FBO mid-gesture.
        val padPx = ceil(WINDOW_PAD_DP * density).toInt()
        val rawW = ceil(maxOf(dropW, maxDropW)).toInt() + 2 * padPx
        val rawH = ceil(maxOf(dropH, maxDropH)).toInt() + 2 * padPx
        if (rawW <= 0 || rawH <= 0) return false

        var renderScale = 1f
        if (rawW > maxCanvasEdge) renderScale = minOf(renderScale, maxCanvasEdge.toFloat() / rawW)
        if (rawH > maxCanvasEdge) renderScale = minOf(renderScale, maxCanvasEdge.toFloat() / rawH)
        if (renderScale < MIN_SCALE) return false

        val reqNodeW = bucketUp(ceil(rawW * renderScale).toInt().coerceAtLeast(1)).coerceAtMost(maxCanvasEdge)
        val reqNodeH = bucketUp(ceil(rawH * renderScale).toInt().coerceAtLeast(1)).coerceAtMost(maxCanvasEdge)
        val nodeW = if (allocatedNodeW > reqNodeW + NODE_SHRINK_HYSTERESIS_PX) {
            reqNodeW
        } else {
            maxOf(allocatedNodeW, reqNodeW).coerceAtMost(maxCanvasEdge)
        }
        val nodeH = if (allocatedNodeH > reqNodeH + NODE_SHRINK_HYSTERESIS_PX) {
            reqNodeH
        } else {
            maxOf(allocatedNodeH, reqNodeH).coerceAtMost(maxCanvasEdge)
        }
        if (nodeW < ceil(dropW * renderScale).toInt() + 4 ||
            nodeH < ceil(dropH * renderScale).toInt() + 4 ||
            nodeW > maxCanvasEdge || nodeH > maxCanvasEdge
        ) {
            return false
        }
        if (allocatedNodeW != nodeW || allocatedNodeH != nodeH) {
            allocatedNodeW = nodeW
            allocatedNodeH = nodeH
            node.setPosition(0, 0, nodeW, nodeH)
            hasCachedUniforms = false
        }

        val s = renderScale
        val scaledOriginX = floor(dropCx * s - nodeW * 0.5f)
        val scaledOriginY = floor(dropCy * s - nodeH * 0.5f)

        val recording = node.beginRecording()
        try {
            recording.translate(-scaledOriginX, -scaledOriginY)
            if (s != 1f) {
                recording.scale(s, s)
            }
            recordContent(recording)
        } finally {
            node.endRecording()
        }

        val scaledDensity = density * s
        val clampedLift = lift.coerceIn(0f, 1.15f)
        val clampedVelStretch = velocityStretch.coerceIn(-0.25f, 1.20f)
        val glassProgress = smoothstep(0.05f, 0.72f, clampedLift)
        val darkFlag = if (isDark) 1f else 0f

        val localCx = dropCx * s - scaledOriginX
        val localCy = dropCy * s - scaledOriginY
        val halfW = (dropW * 0.5f * s).coerceAtLeast(1f)
        val halfH = (dropH * 0.5f * s).coerceAtLeast(1f)
        val barL = -scaledOriginX
        val barT = -scaledOriginY
        val barR = barWidth * s - scaledOriginX
        val barB = barHeight * s - scaledOriginY

        val clampMinX: Float
        val clampMinY: Float
        val clampMaxX: Float
        val clampMaxY: Float
        if (sceneBoundsLocal != null && sceneBoundsLocal.width() > 1f && sceneBoundsLocal.height() > 1f) {
            clampMinX = (sceneBoundsLocal.left * s - scaledOriginX).coerceIn(0f, nodeW - 2f)
            clampMinY = (sceneBoundsLocal.top * s - scaledOriginY).coerceIn(0f, nodeH - 2f)
            clampMaxX = (sceneBoundsLocal.right * s - scaledOriginX).coerceIn(clampMinX + 1f, nodeW.toFloat())
            clampMaxY = (sceneBoundsLocal.bottom * s - scaledOriginY).coerceIn(clampMinY + 1f, nodeH.toFloat())
        } else {
            clampMinX = 0f
            clampMinY = 0f
            clampMaxX = nodeW.toFloat()
            clampMaxY = nodeH.toFloat()
        }

        val uniformsChanged = !hasCachedUniforms ||
            uDropCx != localCx || uDropCy != localCy || uHalfW != halfW || uHalfH != halfH ||
            uBarL != barL || uBarT != barT || uBarR != barR || uBarB != barB ||
            uSceneL != clampMinX || uSceneT != clampMinY || uSceneR != clampMaxX || uSceneB != clampMaxY ||
            uVelStretch != clampedVelStretch || uGlass != glassProgress || uDensity != scaledDensity || uIsDark != darkFlag

        if (uniformsChanged) {
            uDropCx = localCx
            uDropCy = localCy
            uHalfW = halfW
            uHalfH = halfH
            uBarL = barL
            uBarT = barT
            uBarR = barR
            uBarB = barB
            uSceneL = clampMinX
            uSceneT = clampMinY
            uSceneR = clampMaxX
            uSceneB = clampMaxY
            uVelStretch = clampedVelStretch
            uGlass = glassProgress
            uDensity = scaledDensity
            uIsDark = darkFlag
            hasCachedUniforms = true

            shader.setFloatUniform("droplet", localCx, localCy, halfW, halfH)
            shader.setFloatUniform("barRect", barL, barT, barR, barB)
            shader.setFloatUniform("sceneBounds", clampMinX, clampMinY, clampMaxX, clampMaxY)
            shader.setFloatUniform("optics", clampedVelStretch, glassProgress, scaledDensity, darkFlag)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }

        val save = canvas.save()
        try {
            if (s != 1f) {
                val inv = 1f / s
                canvas.scale(inv, inv)
            }
            canvas.translate(scaledOriginX, scaledOriginY)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(save)
        }
        return true
    }

    internal fun clear() {
        allocatedNodeW = 0
        allocatedNodeH = 0
        hasCachedUniforms = false
        node.setRenderEffect(null)
        node.discardDisplayList()
    }

    private fun bucketUp(px: Int): Int = ((px + NODE_BUCKET_PX - 1) / NODE_BUCKET_PX) * NODE_BUCKET_PX

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        private const val WINDOW_PAD_DP = 14f
        private const val NODE_BUCKET_PX = 64
        private const val NODE_SHRINK_HYSTERESIS_PX = 192
        private const val MAX_EDGE = 2_048
        private const val MIN_SCALE = 0.55f

        private const val SOURCE = """
            uniform shader content;
            uniform float4 droplet;     // cx, cy, halfW, halfH in node-local scaled px
            uniform float4 barRect;     // barLeft, barTop, barRight, barBottom in node-local scaled px
            uniform float4 sceneBounds; // valid live window bounds inside node-local scaled px
            uniform float4 optics;      // velStretch (-0.25..1.20), glassProgress (0..1), scaledDensity, isDark

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);
            const float2 KEY_LIGHT = float2(-0.56000, -0.82849); // normalize(float2(-0.56, -0.828))

            float3 sampleScene(float2 p, float isDark) {
                float2 safeP = clamp(p, sceneBounds.xy + 1.0, sceneBounds.zw - 1.0);
                half4 px = content.eval(safeP);
                float ground = mix(0.95, 0.02, isDark);
                return float3(px.rgb) + ground * (1.0 - px.a);
            }

            half4 main(float2 coord) {
                float2 c = coord - droplet.xy;
                float2 halfSize = droplet.zw;
                float dp = max(optics.z, 0.5);
                if (abs(c.x) > halfSize.x + 10.5 * dp || abs(c.y) > halfSize.y + 10.5 * dp) {
                    return half4(0.0);
                }

                float rad = min(halfSize.x, halfSize.y);
                float2 q = abs(c) - halfSize + rad;
                float2 corner = max(q, 0.0);
                float cornerLen = length(corner);
                float dist = cornerLen + min(max(q.x, q.y), 0.0) - rad;
                float coverage = clamp(0.5 - dist / 1.25, 0.0, 1.0);

                float glass = optics.y;
                float isDark = optics.w;

                // Soft 3D floating ambient drop shadow right outside the elevated crystal droplet.
                float2 sc = c - float2(0.0, 1.5 * dp);
                float2 sq = abs(sc) - halfSize + rad;
                float shadowDist = length(max(sq, 0.0)) + min(max(sq.x, sq.y), 0.0) - rad;
                float shadowFade = 1.0 - smoothstep(0.0, 8.5 * dp, max(shadowDist, 0.0));
                float shadowAlpha = exp(-max(shadowDist, 0.0) / (3.0 * dp))
                    * shadowFade * mix(0.13, 0.24, isDark) * glass;

                if (coverage <= 0.0) {
                    return shadowAlpha > 0.001 ? half4(0.0, 0.0, 0.0, half(shadowAlpha)) : half4(0.0);
                }

                float velStretch = max(optics.x, 0.0);

                // Analytic outward unit normal of the stadium capsule.
                float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float2 normal = (cornerLen > 0.001 ? corner / cornerLen : axis) * sign(c);
                normal /= max(length(normal), 0.001);

                // iOS 26 C1 convex-dome meniscus inward-recess refraction:
                // - Samples outward (+normal * amp * bump) so the 1.0x outer glass bar visibly
                //   shrinks & curves inward (╰____╯ / ╭────╮) across top, bottom, AND semicircular
                //   end caps, exposing crystal-clear protruding top/bottom collars.
                // - Exact zero derivative at peakDp lands smoothly into the flat recessed inner bar,
                //   then decays cleanly to 0 at bevelEndDp for a distortion-free central core.
                float depth = max(-dist / dp, 0.0);
                float shortSideDp = rad * 2.0 / dp;
                float barHDp = max(barRect.w - barRect.y, 1.0) / dp;
                float nx2 = normal.x * normal.x;
                float peakDp = clamp(shortSideDp * 0.090, 5.6, 6.8);
                float bevelEndDp = clamp(shortSideDp * 0.28, 17.5, 22.0);
                float riseU = clamp((depth + 0.50) / (peakDp + 0.50), 0.0, 1.0);
                float rise = pow(max(sin(riseU * 1.5707963), 0.0001), 0.72);
                float bump = rise * (1.0 - smoothstep(peakDp + 1.2, bevelEndDp, depth));

                float ampDp = (barHDp * 0.044 + 0.32 * velStretch) * glass;
                float2 bend = normal * (ampDp * dp * bump);
                float2 samplePos = coord + bend;

                // Continuous 5-band Cauchy spectral dispersion along the 4 curved bar-entry corner
                // slopes & semicircular meniscus shoulders (clean & crisp on flat straight center).
                float shoulderMask = smoothstep(0.14, 0.48, abs(normal.x * normal.y) * 2.2);
                float cornerSpread = clamp(nx2 / 0.22, 0.0, 1.0);
                float rimPrism = (1.0 - smoothstep(0.0, 4.6, depth)) * 0.42 * shoulderMask;
                float dispFactor = max(bump, rimPrism);
                float spread = (0.36 + 0.07 * velStretch) * cornerSpread * dispFactor * glass;
                float2 prismVec = normal * (ampDp * dp * dispFactor * spread);
                float prismLen = length(prismVec);
                float3 scene;
                if (prismLen > 0.90 * dp) {
                    float3 cR = sampleScene(samplePos - prismVec, isDark);
                    float3 cY = sampleScene(samplePos - prismVec * 0.5, isDark);
                    float3 cG = sampleScene(samplePos, isDark);
                    float3 cC = sampleScene(samplePos + prismVec * 0.5, isDark);
                    float3 cB = sampleScene(samplePos + prismVec, isDark);
                    scene = float3(
                        0.5 * (cR.r + cY.r),
                        0.25 * cY.g + 0.5 * cG.g + 0.25 * cC.g,
                        0.5 * (cC.b + cB.b)
                    );
                } else if (prismLen > 0.20) {
                    scene = sampleScene(samplePos, isDark);
                    scene.r = sampleScene(samplePos - prismVec, isDark).r;
                    scene.b = sampleScene(samplePos + prismVec, isDark).b;
                } else {
                    scene = sampleScene(samplePos, isDark);
                }

                // Locate the exact refracted outer-bar silhouette (╰____╯ / ╭────╮) inside the droplet.
                float2 barCenter = 0.5 * (barRect.xy + barRect.zw);
                float2 barHalf = max(0.5 * (barRect.zw - barRect.xy), float2(1.0));
                float2 bp = samplePos - barCenter;
                float barRad = min(barHalf.x, barHalf.y);
                float2 bq = abs(bp) - barHalf + barRad;
                float barDist = length(max(bq, 0.0)) + min(max(bq.x, bq.y), 0.0) - barRad;
                float insideBar = 1.0 - smoothstep(-0.75 * dp, 0.75 * dp, barDist);

                float luma = dot(scene, LUMA);
                float sat = 1.0 + 0.18 * glass;
                float3 color = mix(float3(luma), scene, sat);
                if (isDark > 0.5) {
                    color = color + 0.018 * glass * insideBar;
                } else {
                    // Subtle optical edge shading along the recessed bar contour keeps the
                    // ╰____╯ / ╭────╮ shrink effect crisp even over blank white light-mode pages.
                    float recessEdge = (1.0 - smoothstep(0.0, 1.35 * dp, abs(barDist)))
                        * smoothstep(0.68, 0.96, luma) * 0.055 * glass;
                    color = color * (1.0 - recessEdge)
                        + mix(0.008, 0.020, insideBar) * glass;
                }

                // Outer droplet crystal boundary:
                // - Crisp luminous top/bottom specular rim with dual-lobe studio highlight + subtle
                //   emerald/cyan/amber prism bevel at the 4 straight-bar corner junctions;
                // - Clean 3D dark optical silhouette on vertical caps in dark mode so the elevated
                //   droplet separates crisply from the recessed bar beneath it.
                if (depth < 5.2 && glass > 0.001) {
                    float2 barP0 = coord - barCenter;
                    float2 bq0 = abs(barP0) - barHalf + barRad;
                    float barDist0 = length(max(bq0, 0.0)) + min(max(bq0.x, bq0.y), 0.0) - barRad;
                    float nearBarEdge = 1.0 - smoothstep(0.0, 10.5 * dp, abs(barDist0));
                    float straightBarSide = 1.0 - smoothstep(
                        barHalf.x - barRad - 4.0 * dp,
                        barHalf.x - barRad + 4.0 * dp,
                        abs(barP0.x)
                    );
                    float cornerJunction = shoulderMask * nearBarEdge * straightBarSide;

                    float rimWidth = mix(0.95, 1.55, cornerJunction);
                    float rimCenter = mix(0.42, 0.56, cornerJunction);
                    float rimLine = 1.0 - smoothstep(0.0, rimWidth, abs(depth - rimCenter));
                    float innerSheen = exp(-depth * 0.42) * smoothstep(0.35, 1.8, depth);
                    float vertBias = abs(normal.y);
                    float topBotArc = smoothstep(0.46, 0.86, vertBias);
                    float facing = dot(normal, KEY_LIGHT);
                    float keyLobe = facing > 0.0 ? pow(facing, 3.0) : 0.0;
                    float bounceLobe = facing < 0.0 ? pow(-facing, 3.0) : 0.0;

                    float sideRimFloor = mix(0.22, 0.04, isDark);
                    float arcWeight = mix(sideRimFloor, 1.0, topBotArc);
                    float rimProfile = (0.30 + 0.34 * (keyLobe + bounceLobe)) * arcWeight * (1.0 - 0.25 * cornerJunction);

                    float3 prismTint = mix(
                        float3(0.99, 0.995, 1.0),
                        mix(float3(0.76, 1.18, 0.88), float3(0.76, 1.14, 1.18), step(0.0, normal.y)),
                        cornerJunction * 0.82
                    );

                    float rimStrength = mix(0.92, 0.72, isDark) * glass;
                    float softKnee = 1.0 - clamp(luma, 0.0, 1.0) * mix(0.25, 0.55, cornerJunction);
                    float3 specular = prismTint * ((rimLine * rimProfile + innerSheen * 0.09 * topBotArc) * rimStrength * softKnee);

                    // Crisp emerald-cyan-amber corner prism bevel at the 4 straight-bar junctions.
                    float dg = (depth - 1.30) / 1.05;
                    float da = (depth - 2.45) / 0.95;
                    float gBand = exp(-(dg * dg)) * cornerJunction
                        * (1.0 - clamp(luma, 0.0, 1.0) * 0.70) * glass;
                    float aBand = exp(-(da * da)) * cornerJunction
                        * (1.0 - clamp(luma, 0.0, 1.0) * 0.80) * glass;
                    float3 cornerGlint = mix(0.45, 1.0, isDark) * (
                        float3(0.036, 0.078, 0.044) * gBand
                        + float3(0.056, 0.032, 0.016) * aBand
                    );

                    float darkBackdropMask = 1.0 - smoothstep(0.20, 0.55, luma);
                    float sideDark = (1.0 - topBotArc) * 0.72 * isDark * darkBackdropMask * glass;
                    float lumaRimDark = smoothstep(0.55, 0.94, luma) * 0.14 * glass;
                    float themeRimDark = (1.0 - isDark) * 0.06 * glass;
                    float hairline = (1.0 - smoothstep(0.0, 1.05, depth)) * max(sideDark, max(lumaRimDark, themeRimDark));
                    color = color * (1.0 - hairline) + specular + cornerGlint;
                }

                float3 premulRgb = clamp(color, 0.0, 1.0) * coverage;
                float outAlpha = coverage + shadowAlpha * (1.0 - coverage);
                return half4(half3(premulRgb), half(outAlpha));
            }
        """
    }
}
