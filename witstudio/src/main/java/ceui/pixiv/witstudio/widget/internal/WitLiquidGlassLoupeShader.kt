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
 * Calibrated 1:1 against 60fps iOS 26 UITabBar capture (`bar_0099.png`, `bar_0135.png`,
 * `bar_0462.png`) using the same physically-true convex water-droplet optics as
 * [WitLiquidGlassShader]:
 * - **Two-plane recording**: Plane 1 (1.0× live backdrop + outer glass bar platter + fading
 *   resting pill) followed by Plane 2 (`1.17×` crisp vector-magnified selected tab icons/labels).
 * - **Convex magnifying meniscus bevel (`-normal`, `9–15dp`, `pow(1 - t, 2.2)`)**:
 *   - Flat interior plateau (`depth >= bevelDp`): exact `0.0` distortion and `0.0` chromatic
 *     aberration so centered icons and labels stay 100% razor-sharp.
 *   - Curved perimeter (`0..bevelDp`): bends inward (`-normal * refract * slope`) so icons
 *     crossing the rim swell, bulge, and wrap lushly around the droplet wall like a real convex
 *     water drop (never shrinking/pinching edges inward, and never sampling off-screen into
 *     black crescents at left/right screen borders).
 * - **Refined jewel-like RGB prism dispersion (`slope * 0.095`)** matching [WitLiquidGlassShader]:
 *   crisp spectral fringes strictly along the curved rim without noisy rainbow smears over artwork.
 * - **Pure crystal interior & crisp directional Fresnel specular rim**: subtle luminous crystal
 *   lift over the bar paired with a razor-thin specular rim and delicate iridescent corner glints.
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
    private var uLift = 0f
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
        val liftOut = clampedLift.coerceIn(0f, 1f)
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
            uLift != liftOut || uGlass != glassProgress || uDensity != scaledDensity || uIsDark != darkFlag

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
            uLift = liftOut
            uGlass = glassProgress
            uDensity = scaledDensity
            uIsDark = darkFlag
            hasCachedUniforms = true

            shader.setFloatUniform("droplet", localCx, localCy, halfW, halfH)
            shader.setFloatUniform("barRect", barL, barT, barR, barB)
            shader.setFloatUniform("sceneBounds", clampMinX, clampMinY, clampMaxX, clampMaxY)
            shader.setFloatUniform("optics", liftOut, glassProgress, scaledDensity, darkFlag)
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
            uniform float4 optics;      // lift (0..1), glassProgress (0..1), scaledDensity, isDark

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);
            const float2 KEY_LIGHT = float2(-0.38176, -0.92426); // normalize(float2(-0.38, -0.92))

            float3 sampleScene(float2 p, float isDark) {
                float2 safeP = clamp(p, sceneBounds.xy + 1.0, sceneBounds.zw - 1.0);
                half4 px = content.eval(safeP);
                float ground = mix(0.95, 0.02, isDark);
                return float3(px.rgb) + ground * (1.0 - px.a);
            }

            float sdCapsule(float2 p, float2 halfSize) {
                float r = min(halfSize.x, halfSize.y);
                float2 q = abs(p) - halfSize + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
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

                float glass = optics.y;
                float isDark = optics.w;

                // Analytic outward unit normal of the stadium capsule.
                float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float2 normal = (length(corner) > 0.001 ? normalize(corner) : axis) * sign(c);
                normal /= max(length(normal), 0.001);

                // Strictly monotonic convex meniscus bevel (~11.5dp):
                // - Vertically (top/bottom protruding crescents): refractDp = 4.5dp (< 9dp protrusion),
                //   so the protruding top/bottom glass collar clearly reveals the live background
                //   outside the bar AND the outer bar's crisp top/bottom specular rim passing right
                //   underneath the glass ("上下漏边"), with zero U-turn or double echo!
                // - Horizontally (left/right curved walls): refractDp = 8.5dp for lush convex liquid
                //   wrapping of icons during drag, without ever shrinking edges or sampling off-screen.
                float depth = max(-dist / dp, 0.0);
                float shortSideDp = rad * 2.0 / dp;
                float bevelDp = clamp(shortSideDp * 0.15, 8.0, 11.5);
                float t = clamp(depth / bevelDp, 0.0, 1.0);
                float invT = 1.0 - t;
                float slope = invT * invT;

                float refractDp = mix(8.5, 4.5, normal.y * normal.y) * glass;
                float2 bend = -normal * dp * refractDp * slope;
                float2 samplePos = coord + bend;

                // Refined jewel-like RGB prism dispersion strictly on the curved meniscus rim.
                float spread = slope * 0.14 * glass;
                float3 scene = sampleScene(samplePos, isDark);
                if (spread > 0.0015) {
                    scene.r = sampleScene(coord + bend * (1.0 - spread), isDark).r;
                    scene.b = sampleScene(coord + bend * (1.0 + spread), isDark).b;
                }

                // Distinguish refracted samples inside the outer bar vs in the protruding collar ("漏边").
                float2 barCenter = 0.5 * (barRect.xy + barRect.zw);
                float2 barHalf = 0.5 * (barRect.zw - barRect.xy);
                float barDist = sdCapsule(samplePos - barCenter, barHalf);
                float insideBar = 1.0 - smoothstep(-1.2 * dp, 1.2 * dp, barDist);

                // Pure crystal liquid-glass clarity: subtle interior lift over the bar, and clean
                // transparent optical depth in the protruding top/bottom/side collar so the outer
                // bar border and protruding glass crescents read unmistakably over dark & bright feeds.
                float luma = dot(scene, LUMA);
                float sat = 1.0 + 0.10 * glass;
                float3 color = mix(float3(luma), scene, sat);
                if (isDark > 0.5) {
                    float interiorLift = 0.026 * glass * insideBar;
                    float collarShade = 1.0 - (1.0 - insideBar) * 0.18 * glass;
                    color = color * collarShade + interiorLift;
                } else {
                    float interiorLift = 0.032 * glass * insideBar;
                    color = mix(color, float3(1.0), 0.045 * glass * insideBar) + interiorLift;
                }

                // Crisp paired directional Fresnel specular rim + subtle inner dome sheen.
                // Past depth > 11dp both rimLine (0) and innerSheen (<0.0006) vanish below 8-bit LSB.
                if (depth < 11.0 && glass > 0.001) {
                    float rimLine = 1.0 - smoothstep(0.0, 1.15, abs(depth - 0.50));
                    float vertBias = abs(normal.y);
                    float facing = dot(normal, KEY_LIGHT);
                    float keyLobe = facing > 0.0 ? pow(facing, 3.2) : 0.0;
                    float bounceLobe = facing < 0.0 ? pow(-facing, 3.2) : 0.0;
                    float rimProfile = 0.11 + 0.27 * vertBias + 0.25 * (keyLobe + bounceLobe);

                    // Delicate cyan/blue & warm-gold iridescent prism sheen at curved shoulders.
                    float cornerMask = clamp(abs(normal.x * normal.y) * 2.5, 0.0, 1.0);
                    float3 prismTint = mix(
                        float3(1.0, 1.0, 1.0),
                        mix(float3(1.12, 1.02, 0.90), float3(0.85, 1.06, 1.18), step(0.0, normal.y)),
                        cornerMask * 0.58
                    );

                    // Tight inner dome sheen decaying within ~3dp so interior stays crystal clear.
                    float innerSheen = exp(-depth * 0.50) * smoothstep(0.45, 2.0, depth);
                    float rimStrength = mix(1.15, 0.96, isDark) * glass;
                    float3 specular = prismTint * (rimLine * rimProfile * rimStrength)
                        + float3(1.0) * (innerSheen * (0.04 + 0.09 * keyLobe) * rimStrength);
                    color += specular;

                    // Subtle hairline definition on light backgrounds so protruding glass edge reads over white.
                    if (isDark < 0.5) {
                        float hairline = (1.0 - smoothstep(0.0, 1.0, depth)) * 0.10 * glass;
                        color *= (1.0 - hairline);
                    }
                }

                return half4(half3(clamp(color, 0.0, 1.0) * coverage), half(coverage));
            }
        """
    }
}
