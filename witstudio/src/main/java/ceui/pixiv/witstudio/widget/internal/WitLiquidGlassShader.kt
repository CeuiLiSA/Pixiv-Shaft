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

    override fun updateOptics(
        width: Int, height: Int, radius: Float, density: Float, dark: Boolean, clear: Boolean,
        tint: Int, clearDimming: Float, touchX: Float, touchY: Float, press: Float, stretch: Float,
    ) {
        val scatter = density * if (clear) 0.85f else 1.95f
        if (scatter != blurRadius) {
            blur = RenderEffect.createBlurEffect(scatter, scatter, Shader.TileMode.CLAMP)
            blurRadius = scatter
        }
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("geometry", inset.toFloat(), radius, density)
        shader.setFloatUniform("material", if (clear) 1f else 0f, if (dark) 1f else 0f, clearDimming)
        shader.setFloatUniform("touch", touchX, touchY, press, stretch)
        shader.setColorUniform("tint", tint)
        // A RuntimeShader effect snapshots its current uniforms. Rebuild only on a material,
        // geometry or touch change. Backdrop updates flow through the same display list.
        val lens = RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        patch.setRenderEffect(RenderEffect.createChainEffect(lens, checkNotNull(blur)))
    }

    override fun draw(
        canvas: Canvas, material: WitLiquidGlassPaint, touchX: Float, touchY: Float, press: Float, stretch: Float,
    ) {
        drawPatch(canvas)
    }

    private companion object {
        // The inverse-power bevel, channel split and paired specular lobes are adapted from
        // QWEA0/Liquid-Glass-Android, GlassLensRenderer.kt (ea79089).
        // License: META-INF/wit-liquid-glass-MIT.txt
        private const val SOURCE = """
            uniform shader backdrop;
            uniform float2 size;
            uniform float3 geometry;  // patch inset, corner radius, display density
            uniform float3 material;  // clear, dark, clear dimming
            uniform float4 touch;     // local position, illumination, spring displacement
            layout(color) uniform half4 tint;

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

            float3 sampleScene(float2 p) {
                half4 pixel = backdrop.eval(p);
                float ground = mix(0.92, 0.03, material.y);
                return float3(pixel.rgb) + ground * (1.0 - pixel.a);
            }

            half4 main(float2 coord) {
                float2 p = coord - geometry.x;
                float dp = geometry.z;
                float2 halfSize = size * 0.5;
                float2 centered = p - halfSize;
                if (abs(centered.x) > halfSize.x + dp * 17.0 ||
                    abs(centered.y) > halfSize.y + dp * 17.0) return half4(0.0);

                float shortSide = min(size.x, size.y) / dp;
                float2 fromTouch = p - touch.xy;
                float pressRadius = max(1.0, min(size.x, size.y) * 0.82);
                float contact = 0.0;
                float radius = geometry.y;
                if (touch.z > 0.001 || abs(touch.w) > 0.001) {
                    contact = exp(-dot(fromTouch, fromTouch) / (pressRadius * pressRadius));
                    float2 grip = clamp((touch.xy - halfSize) / max(halfSize, 1.0), -1.0, 1.0);
                    float elastic = touch.w * dp * min(1.0, shortSide / 48.0);
                    // Preserve a clean capsule silhouette: gentle uniform jelly swell with a
                    // restrained pull lean, avoiding lopsided contour bulges.
                    centered -= grip * float2(1.8, 1.4) * elastic;
                    halfSize += (1.6 + abs(grip) * 0.55) * elastic;
                    radius = clamp(radius + elastic * 1.6, 0.0, min(halfSize.x, halfSize.y));
                }

                // Deform the surface before calculating normals, so the lens and its highlights
                // move with the shape. The foreground View and the hit box do not move.
                float2 q = abs(centered) - halfSize + radius;
                float2 corner = max(q, 0.0);
                float distance = length(corner) + min(max(q.x, q.y), 0.0) - radius;
                float coverage = clamp(0.5 - distance / 1.35, 0.0, 1.0);
                if (coverage <= 0.0) return half4(0.0);

                // The analytic normal of the arc joins the normals of the straight sides.
                float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float2 normal = (length(corner) > 0.001 ? normalize(corner) : axis) * sign(centered);
                normal /= max(length(normal), 0.001);
                float depth = max(-distance / dp, 0.0);
                float bevel = clamp(shortSide * 0.32, 2.0, 22.0);
                float t = clamp(depth / bevel, 0.0, 1.0);
                float slope = (pow(1.0 + 4.0 * t, -1.25) - 0.133748) / 0.866252;
                float refraction = min(shortSide * 0.34, mix(20.0, 24.0, material.x));
                float response = contact * touch.z;
                float2 bend = -normal * dp * refraction * slope * (1.0 + contact * touch.w * 0.12);
                bend -= fromTouch * contact * touch.w * 0.032;

                float spread = slope * mix(0.055, 0.085, material.x);
                float3 scene = sampleScene(coord + bend);
                if (spread > 0.001) {
                    scene.r = sampleScene(coord + bend * (1.0 - spread)).r;
                    scene.b = sampleScene(coord + bend * (1.0 + spread)).b;
                }

                float luma = dot(scene, LUMA);
                float saturation = mix(mix(1.22, 1.16, material.y), 1.18, material.x);
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
                float2 light = normalize(mix(float2(-0.84, -0.54), -fromTouch / pressRadius, response * 0.42));
                float facing = dot(normal, light);
                float key = pow(max(facing, 0.0), 5.0);
                float bounce = pow(max(-facing, 0.0), 5.0);
                float line = 1.0 - smoothstep(0.0, 1.05, abs(depth - 0.45));
                float sheen = exp(-depth * 0.34) * smoothstep(0.7, 2.0, depth);
                float gleam = line * (0.085 + 0.44 * (key + bounce)) + sheen * key * 0.24;
                color += gleam * mix(1.0, 0.72, material.y * (1.0 - material.x)) + response * 0.16;

                return half4(half3(clamp(color, 0.0, 1.0) * coverage), half(coverage));
            }
        """
    }
}
