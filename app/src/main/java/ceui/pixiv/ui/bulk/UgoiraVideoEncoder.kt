package ceui.pixiv.ui.bulk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * 帧序列 → H.264 mp4 压制器（`MediaCodec` 硬件编码 + `MediaMuxer` 封装，零第三方依赖、
 * 不引 FFmpeg，APK 增量为 0）。
 *
 * **为什么要有它**:[FrameSequencePlayer] 是纯 CPU 逐帧解 JPEG,50fps(4x 补帧)下解码
 * 预算只有 20ms,机器一降频就跟不上,只能按时间轴丢帧保时长 —— 动作是对的,但看起来一顿一顿。
 * 换成 mp4 后解码走硬件解码器(同样的画面负载不到 CPU 的零头),配合 [UgoiraVideoPlayer]
 * 的「按 PTS 定时 releaseOutputBuffer」呈现,**每一帧都会送显,不再有丢帧策略**。
 *
 * 关键实现点:
 *
 *  - **输入走 GPU Surface**:`createInputSurface()` + EGL,每帧把 Bitmap 上传成纹理画一个
 *    全屏四边形。编码器的 input surface **不支持 `lockCanvas`**,所以必须走 GL;好处是
 *    RGB→YUV 转换由硬件做,比在 Java 里逐像素转快一个数量级。
 *  - **变帧长**:ugoira 每帧延迟可以不等。mp4 的每个 sample 自带 PTS,所以直接把
 *    `delays.txt` 的累积毫秒喂给 `eglPresentationTimeANDROID`,时序**逐帧精确**,
 *    不做任何重采样(重采样 = 丢帧或补重复帧,正是要避免的)。
 *  - **偶数尺寸**:H.264 要求宽高为偶数,而 ugoira 尺寸任意。这里**裁掉**最多 1 像素
 *    (纹理坐标裁边,1:1 映射 + NEAREST 采样,其余像素逐点原样搬运),不做缩放。
 *  - **失败即放弃**:任何一步不对都返回 null,调用方保留 JPEG 帧序列继续用
 *    [FrameSequencePlayer] 播 —— 引入 mp4 不会让任何一条原本能播的动图变得不能播。
 *    编解码器创建/配置这种**设备级**失败会置 [deviceUnsupported],整个会话不再重试。
 */
internal object UgoiraVideoEncoder {

    /** 压制产物在帧目录里的固定文件名(和 `delays.txt` 同级,随帧目录一起被删/被自愈)。 */
    const val VIDEO_FILE_NAME = "video.mp4"

    private const val TMP_SUFFIX = ".tmp"

    /** 压制中的半成品名。被杀在压制途中会留下它,由 [UgoiraEngine.sweepStaleRifeWork] 收。 */
    const val VIDEO_TMP_FILE_NAME = VIDEO_FILE_NAME + TMP_SUFFIX

    private const val MIME = "video/avc"

    /** 关键帧间隔(秒)。1s 足够让循环回卷时的 `seekTo(0)` 精确命中首帧,又不至于撑大体积。 */
    private const val I_FRAME_INTERVAL = 1

    /**
     * 码率预算(bit / 像素 / 帧)。插画是线稿 + 大块平涂,H.264 压得很好,但线条最怕码率不足
     * 时的振铃。0.3 对 500x500@50fps 约 3.7Mbps、5s 一圈 ≈ 2.3MB —— 仍只有同内容 JPEG
     * 帧序列(15~20MB)的一个零头。
     */
    private const val BITS_PER_PIXEL_PER_FRAME = 0.30

    private const val MIN_BITRATE = 2_000_000
    private const val MAX_BITRATE = 24_000_000

    /** 编码器尺寸下限。比这更小的动图直接不压制(硬件编码器普遍不接受极小分辨率)。 */
    private const val MIN_DIMENSION = 32

    /** 少于这个帧数不值得走视频容器(封装开销比内容还大)。 */
    private const val MIN_FRAMES = 2

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 收尾 drain 的墙钟上限:编码器不吐 EOS 时不能无限等。 */
    private const val DRAIN_EOS_TIMEOUT_MS = 10_000L

    /**
     * 本会话是否已确认这台机器压不出 mp4(创建/配置编码器就失败)。这类失败是设备级的,
     * 条条复发 —— 标记后直接跳过压制,别让每条动图都白等一次编码器初始化。
     * 内容级失败(某张帧解不开、磁盘满)不置位。
     */
    @Volatile
    var deviceUnsupported = false
        private set

    /**
     * 把 [files] / [delaysMs] 压成 [target] 这个 mp4。成功返回它,失败返回 null(调用方
     * 保留帧序列回退)。先写 `.tmp` 再 rename,中途被杀不会留下半个能骗过校验的 mp4。
     *
     * 阻塞 CPU/GPU,内部已切 [Dispatchers.IO];[onProgress] 回帧级 0..100。
     *
     * ⚠️ **本方法体内不得有挂起点**:EGL context 绑定在创建它的那条线程上,而
     * `withContext` 只保证「无挂起就不换线程」—— 中间加一个 `delay()` / 别的 suspend 调用,
     * 恢复后很可能落到 IO 池的另一条线程,后续 GL 调用全部失效。要等待请用阻塞式等待,
     * 取消检查用非挂起的 [kotlinx.coroutines.ensureActive]。
     */
    suspend fun encode(
        target: File,
        files: List<File>,
        delaysMs: List<Int>,
        onProgress: (Int) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        if (deviceUnsupported) return@withContext null
        if (files.size < MIN_FRAMES || files.size != delaysMs.size) return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW < MIN_DIMENSION || srcH < MIN_DIMENSION) {
            Timber.tag(UGOIRA_LOG_TAG).i("[mp4] 尺寸 %dx%d 太小,跳过压制", srcW, srcH)
            return@withContext null
        }
        // H.264 要偶数宽高:裁掉最多 1 像素,不缩放(缩放会糊掉线稿)。
        val outW = srcW and 1.inv()
        val outH = srcH and 1.inv()

        val totalMs = delaysMs.sum().coerceAtLeast(1)
        val fps = (files.size * 1000.0 / totalMs).roundToInt().coerceIn(1, 120)
        val bitrate = (outW.toDouble() * outH * fps * BITS_PER_PIXEL_PER_FRAME)
            .toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)

        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        runCatching { tmp.delete() }
        target.parentFile?.mkdirs()

        val t0 = System.currentTimeMillis()
        var codec: MediaCodec? = null
        var input: GlEncoderInput? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(MIME, outW, outH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            // 编码器创建/配置失败 = 设备级,和内容无关:标记后本会话不再尝试。
            val enc = try {
                val c = MediaCodec.createEncoderByType(MIME)
                try {
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (t: Throwable) {
                    runCatching { c.release() } // configure 抛了就地释放,别把实例漏掉
                    throw t
                }
                c
            } catch (t: Throwable) {
                deviceUnsupported = true
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[mp4] 本机 H.264 编码器不可用,本会话不再压制")
                return@withContext null
            }
            codec = enc
            val surface = enc.createInputSurface()
            enc.start()
            input = GlEncoderInput(surface, outW, outH, srcW, srcH)
            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var sampleCount = 0 // 真正写进 mp4 的帧数,收尾要和输入帧数对上
            // drain:把编码器已经吐出来的 sample 写进 muxer。endOfStream 时一直等到 EOS 标志。
            fun drain(endOfStream: Boolean): Boolean {
                val deadline = System.currentTimeMillis() + DRAIN_EOS_TIMEOUT_MS
                while (true) {
                    val idx = enc.dequeueOutputBuffer(
                        info, if (endOfStream) DEQUEUE_TIMEOUT_US else 0L,
                    )
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return false
                            if (System.currentTimeMillis() > deadline) {
                                Timber.tag(UGOIRA_LOG_TAG).w("[mp4] 等 EOS 超时")
                                return false
                            }
                        }

                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 只会来一次,且必须在 muxer.start() 之前拿到(里面带 SPS/PPS)。
                            if (trackIndex < 0) {
                                trackIndex = mux.addTrack(enc.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }

                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // codec config 已经在 outputFormat 里交给 muxer 了,别重复写
                                info.size = 0
                            }
                            if (info.size > 0 && buf != null && muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mux.writeSampleData(trackIndex, buf, info)
                                sampleCount++
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                    }
                }
            }

            // 逐帧:解 JPEG → 上传纹理 → 打 PTS → swapBuffers(= 送一帧进编码器)
            var reuse: Bitmap? = null
            val opts = BitmapFactory.Options().apply { inMutable = true }
            var ptsMs = 0L
            try {
                for ((i, f) in files.withIndex()) {
                    coroutineContext.ensureActive()
                    opts.inBitmap = reuse
                    val bmp = try {
                        BitmapFactory.decodeFile(f.absolutePath, opts)
                    } catch (e: IllegalArgumentException) {
                        // inBitmap 尺寸/格式不匹配:退回不复用
                        opts.inBitmap = null
                        reuse?.recycle()
                        reuse = null
                        BitmapFactory.decodeFile(f.absolutePath, opts)
                    } ?: throw IllegalStateException("decode frame failed: $f")
                    reuse = bmp
                    // 纹理坐标是按首帧尺寸算死的(裁边 + 1:1 搬运),尺寸不一致的帧画进去
                    // 只会得到错位的画面。这种帧序列本来就不该出现;真出现就整条放弃压制,
                    // 交给尺寸无关的 FrameSequencePlayer —— 宁可回退,不出坏片。
                    if (bmp.width != srcW || bmp.height != srcH) {
                        throw IllegalStateException(
                            "frame size mismatch: $f is ${bmp.width}x${bmp.height}, expected ${srcW}x$srcH",
                        )
                    }
                    input.drawBitmap(bmp)
                    input.setPresentationTime(ptsMs * 1_000_000L)
                    input.swapBuffers()
                    ptsMs += delaysMs[i].coerceAtLeast(1)
                    drain(false)
                    onProgress((i + 1) * 100 / files.size)
                }
            } finally {
                reuse?.recycle()
            }

            enc.signalEndOfInputStream()
            if (!drain(true)) throw IllegalStateException("encoder did not report EOS")
            if (!muxerStarted) throw IllegalStateException("muxer never started (no output format)")
            // 一帧都不能少:mp4 的价值就是「补出来的帧真的都在」。硬件编码器理论上不会丢
            // surface 输入(BufferQueue 自带背压),但真丢了就是静默降级成更低帧率 ——
            // 宁可整条回退到 JPEG 帧序列(帧一定齐),也不留一个少帧的 mp4。
            if (sampleCount != files.size) {
                throw IllegalStateException("encoder emitted $sampleCount frames, expected ${files.size}")
            }

            mux.stop()
            mux.release()
            muxer = null
            muxerStarted = false

            runCatching { target.delete() }
            if (!tmp.renameTo(target)) throw IllegalStateException("rename video.mp4.tmp failed")
            Timber.tag(UGOIRA_LOG_TAG).i(
                "[mp4] 压制完成 %s %dx%d %d帧 %dfps %dKB 耗时%dms",
                target.parentFile?.name ?: target.name, outW, outH, files.size, fps, target.length() / 1024,
                System.currentTimeMillis() - t0,
            )
            target
        } catch (c: CancellationException) {
            runCatching { tmp.delete() }
            throw c
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Timber.tag(UGOIRA_LOG_TAG).w(t, "[mp4] 压制失败,保留 JPEG 帧序列回退")
            null
        } finally {
            // 释放顺序:先停编码器,再拆它的 input surface(GL),最后 muxer。muxer 若还没 stop
            // 说明是异常路径,直接 release 丢弃半成品(tmp 已删)。
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { input?.release() }
            muxer?.let { m ->
                if (muxerStarted) runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }
}

/** EGL_RECORDABLE_ANDROID —— 不带这个属性选出来的 config 喂给编码器 surface 会花屏/失败。 */
private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * 编码器输入 Surface 的 EGL/GL 封装:一个纹理 + 一个全屏四边形。
 *
 * 纹理坐标按 [outW]/[srcW] 裁边(H.264 的偶数尺寸要求),配合 `glViewport(0,0,outW,outH)`
 * 得到**逐像素 1:1** 的搬运,再用 NEAREST 采样,不引入任何重采样模糊。V 轴翻转是因为
 * Bitmap 原点在左上、GL 纹理原点在左下。
 */
private class GlEncoderInput(
    private val surface: Surface,
    outW: Int,
    outH: Int,
    srcW: Int,
    srcH: Int,
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var uploaded = false

    private val posBuf: FloatBuffer = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )
    private val texBuf: FloatBuffer

    init {
        val uMax = outW.toFloat() / srcW
        val vMax = outH.toFloat() / srcH
        // 与 posBuf 一一对应,V 翻转:屏幕下边 ↔ 图像第 outH 行(裁掉的是底边那 1 像素)
        texBuf = floatBufferOf(
            0f, vMax, uMax, vMax, 0f, 0f, uMax, 0f,
        )
        // 建到一半失败(驱动不给 config / 建不出 window surface)必须就地拆干净:
        // 构造函数抛出后调用方拿不到实例,也就没人能再来 release,EGLDisplay/context
        // 和编码器的 input surface 会一直漏着。release() 对半成品状态是安全的。
        try {
            setupEgl()
            setupGl(outW, outH)
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0,
        ) { "eglChooseConfig failed" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    private fun setupGl(outW: Int, outH: Int) {
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
            """.trimIndent(),
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
            """.trimIndent(),
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "link program failed: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        // NEAREST + CLAMP:1:1 搬运,不做任何插值
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile shader failed: " + GLES20.glGetShaderInfoLog(id) }
        return id
    }

    fun drawBitmap(bmp: Bitmap) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        // 首帧 texImage2D 建纹理,之后 texSubImage2D 原地更新(省掉每帧重新分配)
        if (uploaded) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            uploaded = true
        }
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swapBuffers() {
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
    }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
