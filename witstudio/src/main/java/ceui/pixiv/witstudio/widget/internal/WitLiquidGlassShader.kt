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
            !largeSurface -> 0f
            clear -> 0.55f
            else -> 2.4f
        }
        val cx = (left + right) * 0.5f * s
        val cy = (top + bottom) * 0.5f * s
        val hw = ((right - left) * 0.5f).coerceAtLeast(0f) * s
        val hh = ((bottom - top) * 0.5f).coerceAtLeast(0f) * s
        val maxRadius = minOf(hw, hh)
        shader.setFloatUniform("bounds", cx, cy, hw, hh)
        shader.setFloatUniform("geometry", inset.toFloat(), (radius * s).coerceIn(0f, maxRadius), scaledDensity)
        shader.setFloatUniform(
            "material",
            if (clear) 1f else 0f,
            if (dark) 1f else 0f,
            clearDimming,
            if (largeSurface) 1f else 0f,
        )
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
        if (scatter > 0.05f) {
            if (blur == null || scatter != blurRadius) {
                blur = RenderEffect.createBlurEffect(scatter, scatter, Shader.TileMode.CLAMP)
                blurRadius = scatter
            }
            patch.setRenderEffect(RenderEffect.createChainEffect(lens, checkNotNull(blur)))
        } else {
            blur = null
            blurRadius = 0f
            patch.setRenderEffect(lens)
        }
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
            uniform float4 material;  // clear, dark, clear dimming, largeSurface
            uniform float4 touch;     // local position, illumination, spring displacement
            uniform float4 morphOrb;  // metaball orbX, orbY, orbRadius, smin blendK (<=0 disables)
            layout(color) uniform half4 tint;

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

            float3 sampleScene(float2 p) {
                half4 pixel = backdrop.eval(p);
                float ground = mix(0.94, 0.03, material.y);
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
                float dp = max(geometry.z, 0.5);
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
                    float cornerLen = length(corner);
                    distance = cornerLen + min(max(q.x, q.y), 0.0) - radius;
                    if (0.5 - distance / 1.35 <= 0.0) return half4(0.0);
                    // C1-smooth interior axis transition avoids 45-degree bevel creases on small corner radii.
                    float axisBlend = max(dp * 3.0, min(halfSize.x, halfSize.y) * 0.12);
                    float wx = smoothstep(-axisBlend, axisBlend, q.x - q.y);
                    float2 axis = normalize(float2(wx, 1.0 - wx));
                    normal = (cornerLen > 0.001 ? corner / cornerLen : axis) * sign(centered);
                    normal /= max(length(normal), 0.001);
                }
                float coverage = clamp(0.5 - distance / 1.35, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);

                // Physical quarter-circle glass meniscus lens refraction:
                // - Flat distortion-free core inside depth >= bevel for crisp readability.
                // - Steep circular-arc bevel (1 - sqrt(1 - u^2)) creates smooth magnification stretch
                //   followed by authentic grazing-angle edge wrap & fold right along the crystal rim.
                float depth = max(-distance / dp, 0.0);
                float isLarge = material.w;
                float aspect = max(halfSize.x, halfSize.y) / max(min(halfSize.x, halfSize.y), 1.0);
                float circleBias = hasOrb ? 0.35 : (1.0 - smoothstep(1.05, 1.85, aspect));
                float bevelFrac = mix(0.255, 0.335, circleBias);
                float bevel = isLarge > 0.5
                    ? clamp(shortSide * 0.18, 4.0, 16.0)
                    : clamp(shortSide * bevelFrac, 4.0, 22.0);
                float u = 1.0 - clamp(depth / max(bevel, 0.5), 0.0, 1.0);
                float circleFrac = 1.0 - sqrt(max(0.0, 1.0 - u * u));

                float refractRatio = isLarge > 0.5 ? 1.85 : mix(2.38, 2.62, material.x);
                float refraction = bevel * refractRatio;
                float pressAmt = clamp(touch.z, 0.0, 1.0);
                float response = contact * pressAmt;
                float2 bend = -normal * dp * refraction * circleFrac
                    * (1.0 + touch.w * (0.16 + 0.08 * contact));
                if (!hasOrb) {
                    // Subtle convex dome magnification on circular buttons and active press,
                    // keeping wide horizontal pills straight across their central reading zone.
                    float domeZoom = (0.048 * circleBias + 0.036 * max(touch.w, 0.0)) * (1.0 - isLarge);
                    bend -= centered * domeZoom * (1.0 - circleFrac * 0.45);
                    bend -= fromTouch * contact * touch.w * 0.028;
                }

                // Continuous Cauchy spectral dispersion along steep curved meniscus edges:
                // 1-tap in the flat center, 3-tap in mild bevel, 5-tap prism spectrum in steep rim.
                float dispStrength = mix(0.095, 0.125, material.x)
                    * (1.0 - 0.25 * isLarge)
                    * (1.0 + pressAmt * 0.48 + response * 0.28);
                float spread = circleFrac * dispStrength;
                float2 baseSample = coord + bend;
                float2 dispVec = bend * spread;
                float dispLen = length(dispVec);
                float3 scene;
                if (dispLen > 1.35 * dp) {
                    float3 cR = sampleScene(baseSample - dispVec);
                    float3 cY = sampleScene(baseSample - dispVec * 0.5);
                    float3 cG = sampleScene(baseSample);
                    float3 cC = sampleScene(baseSample + dispVec * 0.5);
                    float3 cB = sampleScene(baseSample + dispVec);
                    scene = float3(
                        0.5 * (cR.r + cY.r),
                        0.25 * cY.g + 0.5 * cG.g + 0.25 * cC.g,
                        0.5 * (cC.b + cB.b)
                    );
                } else if (dispLen > 0.25) {
                    scene = sampleScene(baseSample);
                    scene.r = sampleScene(baseSample - dispVec).r;
                    scene.b = sampleScene(baseSample + dispVec).b;
                } else {
                    scene = sampleScene(baseSample);
                }

                float luma = dot(scene, LUMA);
                float saturation = mix(mix(1.24, 1.18, material.y), 1.26, material.x) + pressAmt * 0.10;
                float3 vibrant = mix(float3(luma), scene, saturation);
                vibrant = mix(vibrant, float3(tint.rgb), tint.a);
                float3 color;
                if (material.x > 0.5) {
                    color = (vibrant * 0.99 + 0.015) * (1.0 - material.z);
                } else if (isLarge > 0.5) {
                    if (material.y > 0.5) {
                        float transmission = mix(0.84, 0.58, smoothstep(0.18, 0.82, luma));
                        float lift = mix(0.068, 0.012, smoothstep(0.08, 0.55, luma));
                        color = vibrant * transmission + lift;
                    } else {
                        float frost = mix(0.26, 0.10, smoothstep(0.18, 0.85, luma));
                        float lift = mix(0.040, 0.008, smoothstep(0.68, 0.94, luma));
                        color = mix(vibrant, float3(1.0), frost) + lift;
                    }
                } else if (material.y > 0.5) {
                    float transmission = mix(0.92, 0.66, smoothstep(0.18, 0.84, luma));
                    float lift = mix(0.058, 0.012, smoothstep(0.10, 0.60, luma));
                    color = vibrant * transmission + lift;
                } else {
                    // Crystal-clear light glass: nearly transparent over bright surfaces so refracted
                    // detail stays razor sharp, with a subtle adaptive luminous veil over dark imagery.
                    float frost = mix(0.11, 0.02, smoothstep(0.12, 0.82, luma));
                    float lift = mix(0.024, 0.010, smoothstep(0.50, 0.92, luma));
                    color = mix(vibrant, float3(1.0), frost) + lift;
                }

                // Crisp dual-lobe specular rim and glossy inner meniscus sheen sit on the outer
                // crystal surface so clearDimming dims transmitted light without dulling reflections.
                float2 rawLight = mix(float2(-0.74, -0.67), -fromTouch / pressRadius, response * 0.45);
                float2 light = rawLight / max(length(rawLight), 0.001);
                float facing = dot(normal, light);
                float key = facing > 0.0 ? pow(facing, 2.8) : 0.0;
                float bounce = facing < 0.0 ? pow(-facing, 2.8) : 0.0;
                float rimLine = 1.0 - smoothstep(0.0, 1.15, abs(depth - 0.55));
                float innerSheen = exp(-depth * 0.40) * smoothstep(0.35, 1.8, depth);
                float pressRimBoost = 1.0 + pressAmt * 0.45;
                float vertEdge = abs(normal.y);
                float baseRim = mix(0.34, 0.18, material.y);
                float lobeRim = mix(0.56, 0.58, material.y) * (key + bounce);
                float gleam = (rimLine * (baseRim + 0.10 * vertEdge + 0.14 * pressAmt + lobeRim)
                    + innerSheen * (0.070 + 0.065 * vertEdge + 0.10 * pressAmt + key * 0.24 + bounce * 0.14))
                    * pressRimBoost;
                float cornerMask = clamp(abs(normal.x * normal.y) * 2.2, 0.0, 1.0);
                float3 prismTint = mix(
                    float3(1.0),
                    mix(float3(1.08, 1.01, 0.92), float3(0.90, 1.03, 1.12), step(0.0, normal.y)),
                    cornerMask * (0.14 + pressAmt * 0.40)
                );
                // Hairline outer refractive contrast on very bright light-mode backgrounds keeps
                // the pure-white specular rim crisp even directly over #FFFFFF cards.
                float brightEdge = (1.0 - material.y) * smoothstep(0.72, 0.96, luma)
                    * (1.0 - smoothstep(0.0, 0.65, depth)) * 0.065;
                float contactGlow = response * mix(0.045, 0.065, material.y);
                color = color * (1.0 - brightEdge)
                    + prismTint * (gleam * mix(1.0, 0.84, material.y * (1.0 - material.x)))
                    + contactGlow;

                return half4(half3(clamp(color, 0.0, 1.0) * coverage), half(coverage));
            }
        """
    }
}
