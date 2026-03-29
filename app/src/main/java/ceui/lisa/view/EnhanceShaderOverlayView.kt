package ceui.lisa.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import timber.log.Timber

/**
 * AI画质增强过程中的Shader波纹动画覆盖层 (AOSP Pixel充电波纹移植)。
 *
 * 用法:
 * - showEnhancing()  开始增强时调用，淡入动画
 * - hideEnhancing()  增强完成时调用，淡出并隐藏
 */
class EnhanceShaderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private var program = 0
    private var uTime = -1
    private var uResolution = -1
    private var uAlpha = -1

    private var startTime = System.nanoTime()
    @Volatile private var overlayAlpha = 0f

    private var surfaceW = 1f
    private var surfaceH = 1f
    private var glReady = false

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }

    private var fadeAnimator: ValueAnimator? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        visibility = GONE
    }

    // ═══════════════════ Renderer ═══════════════════

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        program = buildProgram(VERT_SRC, FRAG_SRC)
        if (program != 0) {
            uTime = GLES20.glGetUniformLocation(program, "u_time")
            uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
            uAlpha = GLES20.glGetUniformLocation(program, "u_alpha")
            glReady = true
        }
        startTime = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        surfaceW = w.toFloat()
        surfaceH = h.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!glReady) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)

        GLES20.glUniform1f(uTime, (System.nanoTime() - startTime) / 1e9f)
        GLES20.glUniform2f(uResolution, surfaceW, surfaceH)
        GLES20.glUniform1f(uAlpha, overlayAlpha)

        val pos = GLES20.glGetAttribLocation(program, "a_position")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
    }

    // ═══════════════════ Public API ═══════════════════

    /** 开始AI增强时调用，淡入覆盖层动画 */
    fun showEnhancing() {
        startTime = System.nanoTime()
        overlayAlpha = 0f
        visibility = VISIBLE

        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            addUpdateListener { overlayAlpha = it.animatedValue as Float }
            start()
        }
    }

    /** AI增强完成时调用，淡出并隐藏覆盖层 */
    fun hideEnhancing() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(overlayAlpha, 0f).apply {
            duration = 600L
            addUpdateListener { overlayAlpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    visibility = GONE
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        fadeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // ═══════════════════ GL helpers ═══════════════════

    private fun buildProgram(vSrc: String, fSrc: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vSrc)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
        if (v == 0 || f == 0) return 0

        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)

        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Timber.e("Shader program link failed: %s", GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val label = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            Timber.e("Shader compile failed (%s): %s", label, GLES20.glGetShaderInfoLog(sh))
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    companion object {

        private val QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        // ───────── vertex shader ─────────
        private const val VERT_SRC =
            "attribute vec4 a_position;\n" +
            "void main() { gl_Position = a_position; }\n"

        // ───────── fragment shader ─────────
        // AOSP Pixel charging ripple — lightweight version
        // Removed distort (atan+4trig), reuse ring for sparkle, 2 ripples
        private const val FRAG_SRC =
            "precision mediump float;\n" +
            "uniform float u_time;\n" +
            "uniform vec2  u_resolution;\n" +
            "uniform float u_alpha;\n" +
            "\n" +
            "float triangleNoise(vec2 n) {\n" +
            "    n = fract(n * vec2(5.3987, 5.4421));\n" +
            "    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));\n" +
            "    float xy = n.x * n.y;\n" +
            "    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;\n" +
            "}\n" +
            "\n" +
            "float sparkles(vec2 uv, float t) {\n" +
            "    float n = triangleNoise(uv);\n" +
            "    float s = 0.0;\n" +
            "    for(int i = 0; i < 2; i++) {\n" +
            "        float fi = float(i);\n" +
            "        float l = fi * 0.01;\n" +
            "        float o = smoothstep(n - l, l + 0.1, n);\n" +
            "        o *= abs(sin(PI * o * (t + 0.55 * fi)));\n" +
            "        s += o;\n" +
            "    }\n" +
            "    return s;\n" +
            "}\n" +
            "\n" +
            // single distance → derive circle + ring, no sqrt recompute
            "float rippleWave(vec2 p, vec2 ctr, float prog, float maxR, float t) {\n" +
            "    float ip = 1.-prog;\n" +
            "    float radius = (1.-ip*ip*ip) * maxR;\n" +
            "    if(radius < 1.) return 0.;\n" +
            "    float blur = mix(1.25, .5, prog);\n" +
            "    float bh = blur*.5;\n" +
            "    float d = distance(p, ctr);\n" +
            // softCircle (filled)
            "    float circ = 1.-smoothstep(1.-bh, 1.+bh, d/(radius*1.2));\n" +
            // softRing (compute once, reuse for sparkle)
            "    float th = radius*.25;\n" +
            "    float outer = 1.-smoothstep(1.-bh, 1.+bh, d/(radius+th));\n" +
            "    float inner = 1.-smoothstep(1.-bh, 1.+bh, d/(radius-th));\n" +
            "    float ring = outer - inner;\n" +
            // fading
            "    float fadeIn = smoothstep(0., .1, prog);\n" +
            "    float fadeRing = min(fadeIn, 1.-smoothstep(.3, 1., prog));\n" +
            "    float fadeCirc = 1.-smoothstep(0., .2, prog);\n" +
            "    float fadeSp = min(fadeIn, 1.-smoothstep(.4, 1., prog));\n" +
            "    float sp = sparkles(p - mod(p, vec2(2.)), t*.00175) * ring * fadeSp * .3;\n" +
            "    return max(circ*fadeCirc, ring*fadeRing)*.45 + sp;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 p = gl_FragCoord.xy;\n" +
            "    vec2 ctr = u_resolution * .5;\n" +
            "    float maxR = length(u_resolution) * .5;\n" +
            "    float tMs = u_time * 1000.;\n" +
            "    float rip = 0.;\n" +
            "    for(int i = 0; i < 2; i++) {\n" +
            "        float phase = fract(u_time / 4.0 + float(i) * .5);\n" +
            "        rip += rippleWave(p, ctr, phase, maxR, tMs);\n" +
            "    }\n" +
            "    rip = min(rip, 1.);\n" +
            "    vec3 col = vec3(.7,.85,1.) * rip;\n" +
            "    float brightness = max(max(col.r,col.g),col.b);\n" +
            "    float al = clamp(brightness, 0., .55)*u_alpha;\n" +
            "    gl_FragColor = vec4(col, al);\n" +
            "}\n"
    }
}
