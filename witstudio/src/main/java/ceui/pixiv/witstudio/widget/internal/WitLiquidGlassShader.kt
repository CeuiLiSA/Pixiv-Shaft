package ceui.pixiv.witstudio.widget.internal

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

@RequiresApi(33)
internal class WitLiquidGlassShader : WitLiquidGlassRenderer() {
    override val backend: WitLiquidGlassBackend = WitLiquidGlassBackend.REFRACTION
    private val shader = RuntimeShader(SOURCE)
    private var blur: RenderEffect? = null
    private var blurRadius = -1f

    override fun onBackdropCleared() {
        blurRadius = -1f
        blur = null
    }

    override fun updateOptics(
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, density: Float, dark: Boolean, clear: Boolean, largeSurface: Boolean,
        tint: Int, clearDimming: Float, touchX: Float, touchY: Float, press: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail?,
    ) {
        val s = renderScale
        val scaledDensity = density * s
        val scatter = scaledDensity * when {
            clear -> 0.85f
            largeSurface -> 3.0f
            else -> 1.95f
        }
        if (blur == null || scatter != blurRadius) {
            blur = RenderEffect.createBlurEffect(scatter, scatter, Shader.TileMode.CLAMP)
            blurRadius = scatter
        }
        val cx = (left + right) * 0.5f * s
        val cy = (top + bottom) * 0.5f * s
        val hw = ((right - left) * 0.5f).coerceAtLeast(0f) * s
        val hh = ((bottom - top) * 0.5f).coerceAtLeast(0f) * s
        val maxRadius = minOf(hw, hh)
        shader.setFloatUniform("bounds", cx, cy, hw, hh)
        shader.setFloatUniform("geometry", inset.toFloat(), (radius * s).coerceIn(0f, maxRadius), scaledDensity)
        shader.setFloatUniform("material", if (clear) 1f else 0f, if (dark) 1f else 0f, clearDimming)
        shader.setFloatUniform("touch", touchX * s, touchY * s, press, stretch)
        if (tail != null && tail.active) {
            shader.setFloatUniform("morphOrb", tail.orbX * s, tail.orbY * s, tail.orbRadius * s, tail.blendPx * s)
        } else {
            shader.setFloatUniform("morphOrb", 0f, 0f, 0f, 0f)
        }
        shader.setColorUniform("tint", tint)
        // A RuntimeShader effect snapshots its current uniforms. Rebuild only on a material,
        // geometry or touch change. Backdrop updates flow through the same display list.
        val lens = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        patch.setRenderEffect(RenderEffect.createChainEffect(lens, checkNotNull(blur)))
    }

    override fun draw(
        canvas: Canvas,
        material: WitLiquidGlassPaint,
        left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float,
        touchX: Float, touchY: Float, press: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail?,
    ) {
        drawPatch(canvas)
    }

    private companion object {
        // The inverse-power bevel, channel split and paired specular lobes are adapted from
        // QWEA0/Liquid-Glass-Android, GlassLensRenderer.kt (ea79089).
        // License: META-INF/wit-liquid-glass-MIT.txt
        private const val SOURCE = """
            uniform shader backdrop;
            uniform float4 bounds;    // centerX, centerY, halfW, halfH in patch-local scaled px
            uniform float3 geometry;  // patch inset, corner radius, scaled display density
            uniform float3 material;  // clear, dark, clear dimming
            uniform float4 touch;     // local position, illumination, spring displacement
            uniform float4 morphOrb;  // metaball orbX, orbY, orbRadius, smin blendK (<=0 disables)
            layout(color) uniform half4 tint;

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

            float3 sampleScene(float2 p) {
                half4 pixel = backdrop.eval(p);
                float ground = mix(0.92, 0.03, material.y);
                return float3(pixel.rgb) + ground * (1.0 - pixel.a);
            }

            float sdRoundRect(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            float smin(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / max(k, 0.001), 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            float evalMorphSdf(float2 p, float2 bodyCenter, float2 halfSize, float radius) {
                float dOrb = length(p - morphOrb.xy) - morphOrb.z;
                if (halfSize.x <= 0.5 || halfSize.y <= 0.5) return dOrb;
                float dBody = sdRoundRect(p - bodyCenter, halfSize, radius);
                return smin(dOrb, dBody, morphOrb.w);
            }

            half4 main(float2 coord) {
                float2 p = coord - geometry.x;
                float dp = geometry.z;
                float2 halfSize = bounds.zw;
                float2 centered = p - bounds.xy;
                bool hasOrb = morphOrb.w > 0.001 && morphOrb.z > 0.5;
                if (!hasOrb && (halfSize.x <= 0.25 || halfSize.y <= 0.25 ||
                    abs(centered.x) > halfSize.x + dp * 17.0 ||
                    abs(centered.y) > halfSize.y + dp * 17.0)) return half4(0.0);

                float bodyShortPx = min(halfSize.x, halfSize.y) * 2.0;
                float shortSidePx = hasOrb ? max(bodyShortPx, morphOrb.z * 2.0) : bodyShortPx;
                float shortSide = shortSidePx / dp;
                float2 fromTouch = p - touch.xy;
                float pressRadius = max(1.0, shortSidePx * 0.82);
                float contact = 0.0;
                float radius = geometry.y;
                if (!hasOrb && (touch.z > 0.001 || abs(touch.w) > 0.001)) {
                    contact = exp(-dot(fromTouch, fromTouch) / (pressRadius * pressRadius));
                    float2 grip = clamp((touch.xy - bounds.xy) / max(halfSize, 1.0), -1.0, 1.0);
                    float elastic = touch.w * dp * min(1.0, shortSide / 48.0);
                    // Whole-view spring scaling handles primary swell; keep a restrained directional
                    // grip lean & micro bevel swell so glass + foreground stay optically unified.
                    centered -= grip * float2(1.1, 0.85) * elastic;
                    halfSize += (0.55 + abs(grip) * 0.35) * elastic;
                    radius = clamp(radius + elastic * 0.55, 0.0, min(halfSize.x, halfSize.y));
                }

                // Deform the surface before calculating normals, so the lens and its highlights
                // move with the shape. The foreground View and the hit box do not move.
                float distance;
                float2 normal;
                if (hasOrb) {
                    distance = evalMorphSdf(p, bounds.xy, halfSize, radius);
                    float coverageCheck = clamp(0.5 - distance / 1.35, 0.0, 1.0);
                    if (coverageCheck <= 0.0) return half4(0.0);
                    float eps = max(0.75, dp * 0.35);
                    float gx = evalMorphSdf(p + float2(eps, 0.0), bounds.xy, halfSize, radius) -
                               evalMorphSdf(p - float2(eps, 0.0), bounds.xy, halfSize, radius);
                    float gy = evalMorphSdf(p + float2(0.0, eps), bounds.xy, halfSize, radius) -
                               evalMorphSdf(p - float2(0.0, eps), bounds.xy, halfSize, radius);
                    normal = float2(gx, gy);
                    normal /= max(length(normal), 0.001);
                } else {
                    float2 q = abs(centered) - halfSize + radius;
                    float2 corner = max(q, 0.0);
                    distance = length(corner) + min(max(q.x, q.y), 0.0) - radius;
                    if (0.5 - distance / 1.35 <= 0.0) return half4(0.0);
                    // The analytic normal of the arc joins the normals of the straight sides.
                    float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                    normal = (length(corner) > 0.001 ? normalize(corner) : axis) * sign(centered);
                    normal /= max(length(normal), 0.001);
                }
                float coverage = clamp(0.5 - distance / 1.35, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);
                float depth = max(-distance / dp, 0.0);
                float bevel = clamp(shortSide * 0.32, 2.0, 22.0);
                float t = clamp(depth / bevel, 0.0, 1.0);
                float slope = (pow(1.0 + 4.0 * t, -1.25) - 0.133748) / 0.866252;
                float refraction = min(shortSide * 0.34, mix(20.0, 24.0, material.x));
                float pressAmt = clamp(touch.z, 0.0, 1.0);
                float response = contact * pressAmt;
                float2 bend = -normal * dp * refraction * slope * (1.0 + touch.w * (0.14 + 0.08 * contact));
                bend -= fromTouch * contact * touch.w * 0.024;

                float spread = slope * mix(0.055, 0.085, material.x) * (1.0 + pressAmt * 0.85 + response * 0.40);
                float3 scene = sampleScene(coord + bend);
                if (spread > 0.001) {
                    scene.r = sampleScene(coord + bend * (1.0 - spread)).r;
                    scene.b = sampleScene(coord + bend * (1.0 + spread)).b;
                }

                float luma = dot(scene, LUMA);
                float saturation = mix(mix(1.22, 1.16, material.y), 1.18, material.x) + pressAmt * 0.08;
                float3 vibrant = mix(float3(luma), scene, saturation);
                vibrant = mix(vibrant, float3(tint.rgb), tint.a);
                float3 color;
                if (material.x > 0.5) {
                    color = (vibrant * 0.98 + 0.025) * (1.0 - material.z);
                } else if (material.y > 0.5) {
                    float transmission = mix(0.84, 0.56, smoothstep(0.18, 0.82, luma));
                    float lift = mix(0.035, 0.010, smoothstep(0.20, 0.60, luma));
                    color = vibrant * transmission + lift;
                } else {
                    float frost = mix(0.30, 0.12, smoothstep(0.18, 0.85, luma));
                    float lift = mix(0.045, 0.008, smoothstep(0.68, 0.94, luma));
                    color = mix(vibrant, float3(1.0), frost) + lift;
                }

                // Crisp paired specular rim and lit-face sheen sit on the outer glass surface,
                // so clearDimming dims transmitted light without dulling surface reflections.
                // During press, heighten curved-rim crystal gleam & prism glints instead of washing the center white.
                float2 light = normalize(mix(float2(-0.84, -0.54), -fromTouch / pressRadius, response * 0.45));
                float facing = dot(normal, light);
                float key = facing > 0.0 ? pow(facing, 4.6) : 0.0;
                float bounce = facing < 0.0 ? pow(-facing, 4.6) : 0.0;
                float line = 1.0 - smoothstep(0.0, 1.05, abs(depth - 0.45));
                float sheen = exp(-depth * 0.36) * smoothstep(0.6, 2.0, depth);
                float pressRimBoost = 1.0 + pressAmt * 0.48;
                float gleam = (line * (0.085 + 0.44 * (key + bounce)) + sheen * (0.05 * pressAmt + key * 0.24)) * pressRimBoost;
                float cornerMask = clamp(abs(normal.x * normal.y) * 2.2, 0.0, 1.0);
                float3 prismTint = mix(
                    float3(1.0),
                    mix(float3(1.10, 1.02, 0.90), float3(0.88, 1.04, 1.14), step(0.0, normal.y)),
                    cornerMask * pressAmt * 0.45
                );
                float contactGlow = response * mix(0.048, 0.062, material.y);
                color += prismTint * (gleam * mix(1.0, 0.76, material.y * (1.0 - material.x))) + contactGlow;

                return half4(half3(clamp(color, 0.0, 1.0) * coverage), half(coverage));
            }
        """
    }
}
