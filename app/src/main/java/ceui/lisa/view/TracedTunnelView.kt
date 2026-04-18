package ceui.lisa.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * 无限回廊 Shader View — 1:1 port of JCStaff TracedTunnelImageBackground.
 *
 * Uses AGSL RuntimeShader (API 33+) to render a ray-traced tunnel with image tiles.
 * Falls back to a dark gradient on older devices.
 *
 * Usage in XML:
 *   <ceui.lisa.view.TracedTunnelView
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 *
 * Images must be placed in assets/prime_square/ as square JPGs.
 */
class TracedTunnelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fallbackPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(0xFF0A0418.toInt(), 0xFF1A0A30.toInt(), 0xFF0A1A3A.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private var fallbackGradientHeight = 0f

    private var impl: TunnelImpl? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl = TunnelImpl(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (impl == null && h > 0 && fallbackGradientHeight != h.toFloat()) {
            fallbackGradientHeight = h.toFloat()
            fallbackPaint.shader = LinearGradient(
                0f, 0f, 0f, fallbackGradientHeight,
                intArrayOf(0xFF0A0418.toInt(), 0xFF1A0A30.toInt(), 0xFF0A1A3A.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (impl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl!!.draw(canvas, width, height)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl?.start(context)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            impl?.release()
        }
        super.onDetachedFromWindow()
    }
}

// ── Implementation (API 33+) ────────────────────────────────────────────

private const val ATLAS_COLS = 28
private const val ATLAS_ROWS = 16
private const val IMAGE_TILE_SIZE = 200
private const val FADE_DURATION_MS = 1500L

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class TunnelImpl(private val view: View) {

    private val shader = RuntimeShader(SHADER_TRACED_TUNNEL_IMAGE)
    private val paint = Paint().apply { this.shader = this@TunnelImpl.shader }
    private val drawRect = RectF()

    private var atlasShader: BitmapShader? = null
    private var atlasBitmap: android.graphics.Bitmap? = null

    private var startNanos = 0L
    private var currentTime = 0f
    private var fadeAlpha = 0f
    @Volatile private var running = false
    private var atlasLoaded = false

    private var fadeAnimator: ValueAnimator? = null
    private var loadExecutor: java.util.concurrent.ExecutorService? = null

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (startNanos == 0L) startNanos = frameTimeNanos
            currentTime = (frameTimeNanos - startNanos) / 1_000_000_000f
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        startNanos = 0L

        Choreographer.getInstance().postFrameCallback(choreographerCallback)

        if (!atlasLoaded) {
            val executor = Executors.newSingleThreadExecutor()
            loadExecutor = executor
            executor.execute {
                val data = loadAtlas(context)
                if (data != null && running) {
                    view.post {
                        atlasShader = data.shader
                        atlasBitmap = data.bitmap
                        shader.setFloatUniform("iAtlasSize", data.width, data.height)
                        shader.setInputShader("tileImage", data.shader)
                        atlasLoaded = true
                        startFadeIn()
                    }
                }
                executor.shutdown()
            }
        }
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        fadeAnimator?.cancel()
        loadExecutor?.shutdownNow()
        loadExecutor = null
    }

    fun release() {
        stop()
        atlasLoaded = false
        atlasShader = null
        atlasBitmap?.recycle()
        atlasBitmap = null
        fadeAlpha = 0f
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!atlasLoaded) {
            canvas.drawColor(0xFF000000.toInt())
            return
        }

        shader.setFloatUniform("iResolution", w.toFloat(), h.toFloat())
        shader.setFloatUniform("iTime", currentTime)

        paint.alpha = (fadeAlpha * 255).toInt()
        drawRect.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRect(drawRect, paint)
    }

    private fun startFadeIn() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { fadeAlpha = it.animatedValue as Float }
            start()
        }
    }

    private fun loadAtlas(context: Context): AtlasData? {
        return try {
            val assetManager = context.assets
            val files = assetManager.list("prime_square")?.toList()?.shuffled()
            if (files.isNullOrEmpty()) {
                Timber.w("TracedTunnelView: no images found in assets/prime_square/")
                return null
            }

            val atlasW = ATLAS_COLS * IMAGE_TILE_SIZE
            val atlasH = ATLAS_ROWS * IMAGE_TILE_SIZE
            val atlasBitmap = android.graphics.Bitmap.createBitmap(
                atlasW, atlasH, android.graphics.Bitmap.Config.RGB_565
            )
            val atlasCanvas = Canvas(atlasBitmap)

            val options = BitmapFactory.Options().apply {
                inSampleSize = 2
            }
            val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

            for (row in 0 until ATLAS_ROWS) {
                for (col in 0 until ATLAS_COLS) {
                    val index = (row * ATLAS_COLS + col) % files.size
                    val bitmap = assetManager.open("prime_square/${files[index]}").use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    bitmap?.let {
                        val destRect = android.graphics.RectF(
                            (col * IMAGE_TILE_SIZE).toFloat(),
                            (row * IMAGE_TILE_SIZE).toFloat(),
                            ((col + 1) * IMAGE_TILE_SIZE).toFloat(),
                            ((row + 1) * IMAGE_TILE_SIZE).toFloat()
                        )
                        atlasCanvas.drawBitmap(it, null, destRect, tilePaint)
                        it.recycle()
                    }
                }
            }

            val bitmapShader = BitmapShader(
                atlasBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
            )
            AtlasData(bitmapShader, atlasBitmap, atlasW.toFloat(), atlasH.toFloat())
        } catch (e: Exception) {
            Timber.e(e, "TracedTunnelView: failed to load atlas")
            null
        }
    }

    private data class AtlasData(
        val shader: BitmapShader,
        val bitmap: android.graphics.Bitmap,
        val width: Float,
        val height: Float
    )
}

// ── AGSL shader source — identical to JCStaff SHADER_TRACED_TUNNEL_IMAGE ────

private const val SHADER_TRACED_TUNNEL_IMAGE = """
uniform float2 iResolution;
uniform float  iTime;
uniform float2 iAtlasSize;
uniform shader tileImage;

const float GRID_COLS = 28.0;
const float GRID_ROWS = 16.0;
const float TILE_SIZE = 200.0;
const float TOTAL_IMAGES = 448.0;  // GRID_COLS * GRID_ROWS
const float INV_GRID_COLS = 0.0357142857;  // 1.0 / GRID_COLS

float tick(float t, float d) {
    float m = fract(t / d);
    m = m * m * (3.0 - 2.0 * m);  // Faster than double smoothstep
    return (floor(t / d) + m) * d;
}

float2 rot2(float2 v, float c, float s) {
    return float2(v.x * c + v.y * s, -v.x * s + v.y * c);
}

float hash21(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - iResolution * 0.5) / iResolution.y;

    float t = iTime;
    float tickTm = t + tick(t, 6.0) * 0.5;

    // Precompute sin/cos for camera rotation
    float a1 = sin(tickTm * 0.3) * 0.4;
    float a2 = sin(tickTm * 0.1) * 2.0;
    float c1 = cos(a1), s1 = sin(a1);
    float c2 = cos(a2), s2 = sin(a2);

    float3 ro = float3(0.0, 0.0, tickTm);
    float3 r = normalize(float3(uv, 1.0));

    // Apply camera rotation
    r.xz = rot2(r.xz, c1, s1);
    r.xy = rot2(r.xy, c2, s2);

    // Inline ray-plane intersections (avoid function call overhead)
    float dB = r.y < 0.0 ? (-1.0 - ro.y) / r.y : 1e8;
    float dT = r.y > 0.0 ? ( 1.0 - ro.y) / r.y : 1e8;
    float dL = r.x < 0.0 ? (-1.0 - ro.x) / r.x : 1e8;
    float dR = r.x > 0.0 ? ( 1.0 - ro.x) / r.x : 1e8;

    float dH = min(dB, dT);
    float dV = min(dL, dR);
    float d  = min(dH, dV);

    float3 hp = ro + r * d;

    float3 n;
    float2 tuv;
    if (dH < dV) {
        n   = float3(0.0, dB < dT ? 1.0 : -1.0, 0.0);
        tuv = hp.xz + float2(0.0, n.y);
    } else {
        n   = float3(dL < dR ? 1.0 : -1.0, 0.0, 0.0);
        tuv = hp.yz + float2(n.x, 0.0);
    }

    tuv *= 2.0;
    float2 id = floor(tuv);
    float2 luv = tuv - id - 0.5;

    // Tile shape
    float bx = length(max(abs(luv) - 0.42, 0.0)) - 0.05;
    float inside = smoothstep(0.008, 0.0, bx);
    float sh = clamp(0.5 - bx * 10.0, 0.0, 1.0);

    // Atlas lookup - avoid mod() with fract
    float imgIndex = floor(hash21(id) * TOTAL_IMAGES);
    float atlasRow = floor(imgIndex * INV_GRID_COLS);
    float atlasCol = imgIndex - atlasRow * GRID_COLS;

    float2 imgUV = (float2(atlasCol, atlasRow) + luv + 0.5) * TILE_SIZE;
    float3 imgCol = tileImage.eval(imgUV).rgb;

    // Simple lighting (removed specular for performance)
    float3 sampleCol = mix(float3(0.02), imgCol * sh, inside);
    float dif = max(dot(normalize(float3(0.0, 0.0, 3.0) - hp + ro), n), 0.0);
    sampleCol *= dif * 0.35 + 0.65;

    // Fog
    sampleCol *= 1.0 / (1.0 + d * d * 0.02);

    // Gamma correction
    return half4(half3(pow(max(sampleCol, float3(0.0)), float3(0.4545))), 1.0);
}
"""
