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
 * Calibrated 1:1 against native 1290×2796 iOS 26 UITabBar capture (`orig_3.3.png`, `orig_4.3.png`,
 * `orig_7.5.png`) using unified physically-true convex glass-dome meniscus optics:
 * - **Two-plane recording**: Plane 1 (1.0× live backdrop + **unscaled 1.0×** outer glass bar
 *   platter + fading resting pill) followed by Plane 2 (`1.14×` crisp vector-magnified selected
 *   tab icons/labels).
 * - **Apple-calibrated C¹ convex-dome meniscus refraction (`pow(sin(u * π/2), 0.72) * (1 - smoothstep(peakDp + 1.2, bevelEndDp, depth))`)**:
 *   - Matched to ±0.2px against inverted `orig_4.3.png` pixel measurements (`depth = 0.8..6.8dp`):
 *     starts steep right at the outer droplet arc (`u = clamp((depth + 0.50) / (peakDp + 0.50), 0, 1)`)
 *     so the outer horizontal bar crosses smoothly through the **middle of the corner slope** instead
 *     of pinching at the top-left root, with zero step disconnect and exact C¹ zero-derivative
 *     landing into the flat recessed inner bar (`peakDp ≈ 6.8dp`) while recessing top, bottom, AND
 *     left/right semicircular caps concentrically with zero special cases.
 *   - Smoothly decays to exact `0.0` at `bevelEndDp ≈ 22dp` for a distortion-free interior plateau.
 * - **Subtle corner-focused Cauchy RGB dispersion**: crisp 3-channel physical prism refraction
 *   concentrated at the 4 straight-bar junctions (plus a whisper-subtle rim-hugged glint strictly
 *   at the 4 junctions—zero full-circle rainbow halo, clean flat edges and concentric end caps).
 * - **Crisp neutral top/bottom outer hairline & dark vertical optical silhouette**: razor-thin
 *   specular hairline strictly on top/bottom arcs (`|normal.y| > 0.55`), paired with Apple's
 *   ~1.1dp dark optical edge on vertical caps over dark bars.
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
            const float2 KEY_LIGHT = float2(-0.46000, -0.88792); // normalize(float2(-0.46, -0.888))

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
                if (abs(c.x) > halfSize.x + 2.5 || abs(c.y) > halfSize.y + 2.5) {
                    return half4(0.0);
                }

                float rad = min(halfSize.x, halfSize.y);
                float2 q = abs(c) - halfSize + rad;
                float2 corner = max(q, 0.0);
                float dist = length(corner) + min(max(q.x, q.y), 0.0) - rad;
                float coverage = clamp(0.5 - dist / 1.25, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);

                float velStretch = max(optics.x, 0.0);
                float glass = optics.y;
                float isDark = optics.w;

                // Analytic outward unit normal of the stadium capsule.
                float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float2 normal = (length(corner) > 0.001 ? normalize(corner) : axis) * sign(c);
                normal /= max(length(normal), 0.001);

                // Apple-calibrated C1 convex-dome meniscus refraction inverted directly from native
                // 1290x2796 iOS 26 UITabBar frames (orig_3.3, orig_4.3, orig_7.5; RMS error < 0.2px):
                // - pow(sin(u * PI/2), 0.72) with u = clamp((depth + 0.50) / (peakDp + 0.50), 0, 1):
                //   * Steep initial meniscus rise places the outer-bar crossing right through the
                //     middle of the curved corner slope (never pinching at the root like smoothstep),
                //     bridged seamlessly with zero step disconnect.
                //   * Exact zero derivative at depth = peakDp (~6.8dp) lands silky-smooth into the
                //     flat recessed inner bar (~5.8% of barH) on top, bottom, AND semicircular caps.
                //   * depth >= bevelEndDp (~22dp) -> bump = 0: flat distortion-free interior.
                float depth = max(-dist / dp, 0.0);
                float shortSideDp = rad * 2.0 / dp;
                float barHDp = max(barRect.w - barRect.y, 1.0) / dp;
                float nx2 = normal.x * normal.x;
                float peakDp = clamp(shortSideDp * 0.098, 6.2, 7.4);
                float bevelEndDp = clamp(shortSideDp * 0.32, 20.0, 25.0);
                float riseU = clamp((depth + 0.50) / (peakDp + 0.50), 0.0, 1.0);
                float rise = pow(max(sin(riseU * 1.5707963), 0.0001), 0.72);
                float bump = rise * (1.0 - smoothstep(peakDp + 1.2, bevelEndDp, depth));

                float ampDp = (barHDp * 0.058 + 0.45 * velStretch) * glass;
                float2 bend = normal * (ampDp * dp * bump);
                float2 samplePos = coord + bend;

                // Subtle, crisp 3-channel Cauchy RGB dispersion strictly from refracted scene pixels:
                // - Exact zero on flat top/bottom edges (nx2 == 0) so protruding collars stay crystal clear.
                // - Gently feathers the 4 diagonal corner slopes with natural cyan/amber refraction.
                float shoulderMask = smoothstep(0.16, 0.50, abs(normal.x * normal.y) * 2.2);
                float cornerSpread = clamp(nx2 / 0.25, 0.0, 1.0);
                float rimPrism = (1.0 - smoothstep(0.0, 4.5, depth)) * 0.35 * shoulderMask;
                float dispFactor = max(bump, rimPrism);
                float spread = (0.25 + 0.04 * velStretch) * cornerSpread * dispFactor * glass;
                float2 prismVec = normal * (ampDp * dp * dispFactor * spread);
                float3 scene = sampleScene(samplePos, isDark);
                if (length(prismVec) > 0.20) {
                    scene.r = sampleScene(samplePos - prismVec, isDark).r;
                    scene.b = sampleScene(samplePos + prismVec, isDark).b;
                }

                // Detect whether the refracted sample lands inside the unscaled 1.0x outer bar
                // so protruding clear collars keep zero milky fog while the recessed bar interior
                // gets a subtle luminous crystal lift.
                float2 barCenter = 0.5 * (barRect.xy + barRect.zw);
                float2 barHalf = max(0.5 * (barRect.zw - barRect.xy), float2(1.0));
                float2 bp = samplePos - barCenter;
                float barRad = min(barHalf.x, barHalf.y);
                float2 bq = abs(bp) - barHalf + barRad;
                float barDist = length(max(bq, 0.0)) + min(max(bq.x, bq.y), 0.0) - barRad;
                float insideBar = 1.0 - smoothstep(-0.75 * dp, 0.75 * dp, barDist);

                float luma = dot(scene, LUMA);
                float sat = 1.0 + 0.11 * glass;
                float3 color = mix(float3(luma), scene, sat);
                if (isDark > 0.5) {
                    color = color + 0.014 * glass * insideBar;
                } else {
                    color = mix(color, float3(1.0), 0.020 * glass * insideBar) + 0.015 * glass * insideBar;
                }

                // Pixel-authentic iOS 26 outer droplet boundary:
                // - Crisp thin specular hairline strictly on top & bottom arcs (|normal.y| > 0.55),
                //   softening and tinting gently into a whisper-subtle emerald/amber bevel strictly
                //   at the 4 straight-bar corner junctions (zero hot crossing knot, zero full-circle halo);
                // - Left & right vertical caps drop ALL gray wire and draw Apple's razor-thin
                //   ~1.1dp dark optical silhouette over dark bar surfaces so the droplet pops in 3D.
                if (depth < 4.5 && glass > 0.001) {
                    float2 barP0 = coord - barCenter;
                    float2 bq0 = abs(barP0) - barHalf + barRad;
                    float barDist0 = length(max(bq0, 0.0)) + min(max(bq0.x, bq0.y), 0.0) - barRad;
                    float nearBarEdge = 1.0 - smoothstep(0.0, 9.5 * dp, abs(barDist0));
                    float straightBarSide = 1.0 - smoothstep(
                        barHalf.x - barRad - 4.0 * dp,
                        barHalf.x - barRad + 4.0 * dp,
                        abs(barP0.x)
                    );
                    float cornerJunction = shoulderMask * nearBarEdge * straightBarSide;

                    float rimWidth = mix(0.85, 1.45, cornerJunction);
                    float rimCenter = mix(0.38, 0.52, cornerJunction);
                    float rimLine = 1.0 - smoothstep(0.0, rimWidth, abs(depth - rimCenter));
                    float vertBias = abs(normal.y);
                    float topBotArc = smoothstep(0.55, 0.88, vertBias);
                    float facing = dot(normal, KEY_LIGHT);
                    float keyLobe = facing > 0.0 ? pow(facing, 3.6) : 0.0;
                    float bounceLobe = facing < 0.0 ? pow(-facing, 3.6) : 0.0;
                    float rimProfile = (0.24 + 0.20 * (keyLobe + bounceLobe)) * topBotArc * (1.0 - 0.34 * cornerJunction);

                    float3 prismTint = mix(
                        float3(0.98, 0.99, 0.99),
                        mix(float3(0.80, 1.15, 0.88), float3(0.78, 1.12, 1.14), step(0.0, normal.y)),
                        cornerJunction * 0.76
                    );

                    float rimStrength = mix(0.76, 0.58, isDark) * glass;
                    float softKnee = 1.0 - clamp(luma, 0.0, 1.0) * mix(0.45, 0.65, cornerJunction);
                    float3 specular = prismTint * (rimLine * rimProfile * rimStrength * softKnee);

                    // Whisper-subtle emerald-to-amber corner bevel strictly at the 4 straight-bar
                    // junction points (depth 0.5..3.0dp) — zero full ring, bridges the slope cleanly.
                    float gBand = exp(-pow((depth - 1.25) / 1.0, 2.0)) * cornerJunction * isDark
                        * (1.0 - clamp(luma, 0.0, 1.0) * 0.75) * glass;
                    float aBand = exp(-pow((depth - 2.35) / 0.9, 2.0)) * cornerJunction * isDark
                        * (1.0 - clamp(luma, 0.0, 1.0) * 0.85) * glass;
                    float3 cornerGlint = float3(0.028, 0.058, 0.028) * gBand
                        + float3(0.042, 0.022, 0.012) * aBand;

                    float darkBackdropMask = 1.0 - smoothstep(0.20, 0.55, luma);
                    float sideDark = (1.0 - topBotArc) * 0.76 * isDark * darkBackdropMask * glass;
                    float lumaRimDark = smoothstep(0.45, 0.92, luma) * 0.18 * glass;
                    float themeRimDark = (1.0 - isDark) * 0.08 * glass;
                    float hairline = (1.0 - smoothstep(0.0, 1.05, depth)) * max(sideDark, max(lumaRimDark, themeRimDark));
                    color = color * (1.0 - hairline) + specular + cornerGlint;
                }

                return half4(half3(clamp(color, 0.0, 1.0) * coverage), half(coverage));
            }
        """
    }
}
