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
 * AI画质增强过程中的炫酷Shader动画覆盖层。
 *
 * 半透明覆盖在原图上方，展示流光扫描线、极光等离子体、六边形网格、
 * 边缘辉光、粒子闪烁、数据流、进度环等效果。
 *
 * 用法:
 * - showEnhancing()  开始增强时调用，淡入动画
 * - hideEnhancing()  增强完成时调用，淡出并隐藏
 * - setEnhanceProgress(0f..1f)  更新进度
 */
class EnhanceShaderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), Renderer {

    private var program = 0
    private var uTime = -1
    private var uResolution = -1
    private var uProgress = -1
    private var uAlpha = -1

    private var startTime = System.nanoTime()
    @Volatile private var progress = 0f
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
            uProgress = GLES20.glGetUniformLocation(program, "u_progress")
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
        GLES20.glUniform1f(uProgress, progress)
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
        progress = 0f
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

    /** 更新增强进度 [0..1]，进度环会实时反映 */
    fun setEnhanceProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
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
        //
        // Effects:
        //  1. Scanning beams      — cyan / purple / mint 横向扫描光带
        //  2. Aurora plasma       — FBM 噪声驱动的极光等离子体
        //  3. Hexagonal grid      — 扫描线附近浮现的六边形网格 + 脉冲cell
        //  4. Edge glow           — 边缘呼吸辉光
        //  5. Sparkle particles   — 随机闪烁粒子
        //  6. Data streams        — 垂直下落的数据流线
        //  7. Progress ring       — 中央进度环 + 尖端辉光
        //  8. Completion wave     — 完成阶段的绿色波纹
        //
        private const val FRAG_SRC =
            "precision highp float;\n" +
            "uniform float u_time;\n" +
            "uniform vec2  u_resolution;\n" +
            "uniform float u_progress;\n" +
            "uniform float u_alpha;\n" +
            "\n" +
            "#define PI  3.14159265\n" +
            "#define TAU 6.28318530\n" +
            "\n" +
            // ---- hash & noise ----
            "float hash21(vec2 p) {\n" +
            "    p = fract(p * vec2(123.34, 456.21));\n" +
            "    p += dot(p, p + 45.32);\n" +
            "    return fract(p.x * p.y);\n" +
            "}\n" +
            "\n" +
            "float noise(vec2 p) {\n" +
            "    vec2 i = floor(p), f = fract(p);\n" +
            "    f = f * f * (3.0 - 2.0 * f);\n" +
            "    float a = hash21(i), b = hash21(i + vec2(1.0, 0.0)),\n" +
            "          c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));\n" +
            "    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
            "}\n" +
            "\n" +
            "float fbm(vec2 p) {\n" +
            "    float v = 0.0, a = 0.5;\n" +
            "    mat2 R = mat2(0.8776, 0.4794, -0.4794, 0.8776);\n" +
            "    for (int i = 0; i < 5; i++) {\n" +
            "        v += a * noise(p);\n" +
            "        p = R * p * 2.0 + vec2(100.0);\n" +
            "        a *= 0.5;\n" +
            "    }\n" +
            "    return v;\n" +
            "}\n" +
            "\n" +
            // ---- main ----
            "void main() {\n" +
            "    vec2  uv = gl_FragCoord.xy / u_resolution;\n" +
            "    float t = u_time;\n" +
            "    float asp = u_resolution.x / u_resolution.y;\n" +
            "    vec3  col = vec3(0.0);\n" +
            "    float al = 0.0;\n" +
            "\n" +
            // 1. scanning beams
            "    float b1 = exp(-60.0 * pow(uv.y - fract(t * 0.18), 2.0));\n" +
            "    float b2 = exp(-40.0 * pow(uv.y - fract(1.0 - t * 0.14), 2.0));\n" +
            "    float b3 = exp(-30.0 * pow(uv.y - uv.x * 0.3 - fract(t * 0.1), 2.0)) * 0.5;\n" +
            "    col += vec3(0.2, 0.7, 1.0) * b1 * 0.7\n" +
            "         + vec3(0.7, 0.3, 1.0) * b2 * 0.5\n" +
            "         + vec3(0.3, 1.0, 0.7) * b3 * 0.4;\n" +
            "    al += b1 * 0.45 + b2 * 0.35 + b3 * 0.2;\n" +
            "\n" +
            // 2. aurora plasma
            "    vec2 q = vec2(fbm(uv * 3.0 + t * 0.25),\n" +
            "                  fbm(uv * 3.0 + vec2(1.7, 9.2)));\n" +
            "    vec2 r = vec2(fbm(uv * 3.0 + q + vec2(1.7, 9.2) + 0.15 * t),\n" +
            "                  fbm(uv * 3.0 + q + vec2(8.3, 2.8) + 0.12 * t));\n" +
            "    float f = fbm(uv * 3.0 + r);\n" +
            "    vec3 au = mix(vec3(0.05, 0.4, 0.9), vec3(0.9, 0.1, 0.7),\n" +
            "                 clamp(f * f * 4.0, 0.0, 1.0));\n" +
            "    au = mix(au, vec3(0.1, 0.9, 0.8), clamp(length(q), 0.0, 1.0) * 0.6);\n" +
            "    au = mix(au, vec3(0.95, 0.6, 0.1), clamp(r.x, 0.0, 1.0) * 0.2);\n" +
            "    float aA = smoothstep(0.15, 0.85, f) * 0.3;\n" +
            "    col += au * aA;\n" +
            "    al += aA * 0.5;\n" +
            "\n" +
            // 3. hex grid
            "    vec2 hu = uv * 12.0 * vec2(asp, 1.0);\n" +
            "    vec2 hs = vec2(1.0, 1.732);\n" +
            "    vec2 ha = mod(hu, hs) - hs * 0.5;\n" +
            "    vec2 hb = mod(hu + hs * 0.5, hs) - hs * 0.5;\n" +
            "    float da = length(ha), db = length(hb);\n" +
            "    float he = smoothstep(0.08, 0.0, abs(da - db));\n" +
            "    float hr = max(\n" +
            "        smoothstep(0.25, 0.0, abs(uv.y - fract(t * 0.18))),\n" +
            "        smoothstep(0.20, 0.0, abs(uv.y - fract(1.0 - t * 0.14))) * 0.6\n" +
            "    );\n" +
            "    he *= hr;\n" +
            "    vec2 cid = da < db ? floor(hu / hs) : floor((hu + hs * 0.5) / hs);\n" +
            "    float cr = hash21(cid);\n" +
            "    float cp = pow(sin(t * 2.0 + cr * TAU) * 0.5 + 0.5, 3.0) * 0.25 * hr;\n" +
            "    float cdist = da < db ? da : db;\n" +
            "    float cg = smoothstep(0.3, 0.0, cdist) * cp;\n" +
            "    vec3 hc = mix(vec3(0.2, 0.6, 1.0), vec3(0.6, 0.2, 1.0), cr);\n" +
            "    col += hc * (he * 0.4 + cg);\n" +
            "    al += he * 0.25 + cg * 0.2;\n" +
            "\n" +
            // 4. edge glow
            "    float ed = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));\n" +
            "    float eg = exp(-10.0 * ed) * (0.5 + 0.3 * sin(t * 1.5) + 0.2 * sin(t * 2.7));\n" +
            "    vec3 ec = mix(vec3(0.1, 0.5, 1.0), vec3(0.8, 0.15, 0.9),\n" +
            "                 sin(t * 0.7 + uv.x * 3.0 + uv.y * 2.0) * 0.5 + 0.5);\n" +
            "    col += ec * eg * 0.6;\n" +
            "    al += eg * 0.35;\n" +
            "\n" +
            // 5. sparkles
            "    float sp = 0.0;\n" +
            "    for (int i = 0; i < 15; i++) {\n" +
            "        float fi = float(i);\n" +
            "        vec2 spos = vec2(hash21(vec2(fi * 17.3, fi * 3.1)),\n" +
            "                         hash21(vec2(fi * 7.7, fi * 13.9)));\n" +
            "        spos.y = fract(spos.y + t * 0.02 * hash21(vec2(fi, 0.0)));\n" +
            "        spos.x += sin(t * 0.5 + fi) * 0.02;\n" +
            "        float ph = hash21(vec2(fi * 3.1, fi * 7.7));\n" +
            "        float br = pow(max(sin(t * (2.0 + ph * 3.0) + ph * TAU), 0.0), 12.0);\n" +
            "        float sd = length((uv - spos) * vec2(asp, 1.0));\n" +
            "        sp += br * exp(-400.0 * sd * sd);\n" +
            "    }\n" +
            "    col += vec3(0.9, 0.95, 1.0) * sp;\n" +
            "    al += sp * 0.9;\n" +
            "\n" +
            // 6. data streams
            "    for (int i = 0; i < 6; i++) {\n" +
            "        float fi = float(i);\n" +
            "        float xp = hash21(vec2(fi * 11.3, 0.0));\n" +
            "        float spd = 0.3 + hash21(vec2(fi * 7.1, fi * 3.3)) * 0.5;\n" +
            "        float yp = fract(t * spd + hash21(vec2(fi, 1.0)));\n" +
            "        float ll = 0.05 + hash21(vec2(fi, 2.0)) * 0.1;\n" +
            "        float lx = exp(-500.0 * pow(uv.x - xp, 2.0));\n" +
            "        float ly = smoothstep(0.0, 0.01, uv.y - yp)\n" +
            "                 * smoothstep(0.0, 0.01, yp + ll - uv.y);\n" +
            "        col += vec3(0.3, 0.8, 1.0) * lx * ly * 0.5;\n" +
            "        al += lx * ly * 0.2;\n" +
            "    }\n" +
            "\n" +
            // 7. progress ring
            "    vec2  cu = (uv - 0.5) * vec2(asp, 1.0);\n" +
            "    float cd = length(cu);\n" +
            "    float ca = atan(cu.y, cu.x);\n" +
            "    float na = mod((PI * 0.5 - ca) / TAU, 1.0);\n" +
            "    float rr = 0.1, rd = abs(cd - rr);\n" +
            "    float rm = smoothstep(0.003, 0.0, rd);\n" +
            "    float rg = exp(-200.0 * rd * rd) * 0.4;\n" +
            "    float filled = 1.0 - smoothstep(u_progress - 0.005,\n" +
            "                                     u_progress + 0.005, na);\n" +
            "    vec3 pc = mix(vec3(0.2, 0.6, 1.0), vec3(0.2, 1.0, 0.5), u_progress);\n" +
            "    col += pc * (rm + rg) * filled;\n" +
            "    al += (rm * 0.8 + rg * 0.5) * filled;\n" +
            "    col += vec3(0.4, 0.5, 0.6) * rm * (1.0 - filled) * 0.3;\n" +
            "    al += rm * (1.0 - filled) * 0.12;\n" +
            // tip glow
            "    float ta2 = u_progress * TAU - PI * 0.5;\n" +
            "    vec2  tp  = vec2(cos(ta2), sin(ta2)) * rr;\n" +
            "    float tg  = exp(-600.0 * dot(cu - tp, cu - tp));\n" +
            "    col += pc * tg * 2.0;\n" +
            "    al += tg * 0.7;\n" +
            "\n" +
            // 8. progress brightness & completion wave
            "    col *= 1.0 + smoothstep(0.0, 1.0, u_progress) * 0.3;\n" +
            "    if (u_progress > 0.9) {\n" +
            "        float cw = sin((uv.y + uv.x * 0.5) * 20.0 - t * 5.0) * 0.5 + 0.5;\n" +
            "        col += vec3(0.3, 0.9, 0.5) * cw * 0.2 * (u_progress - 0.9) * 10.0;\n" +
            "    }\n" +
            "\n" +
            "    al = clamp(al, 0.0, 0.65) * u_alpha;\n" +
            "    gl_FragColor = vec4(col, al);\n" +
            "}\n"
    }
}
