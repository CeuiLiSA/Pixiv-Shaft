package ceui.pixiv.ui.detail

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * Ugoira mp4 播放器 —— 硬件解码 + **按 PTS 精确送显**,取代 [FrameSequencePlayer] 的
 * 「软解 JPEG + 跟不上就丢帧」。
 *
 * 为什么能不丢帧:
 *
 *  1. **解码不再是瓶颈**。[FrameSequencePlayer] 每帧要在 CPU 上解一张 JPEG(4x 补帧后
 *     预算仅 20ms,降频时根本喂不饱),这里换成 `MediaCodec` 硬件解码器,同样的画面负载
 *     只有原来的零头,50fps 对它是轻载。
 *  2. **送显时刻交给合成器**。每帧用 `releaseOutputBuffer(index, 目标纳秒)` 排进
 *     SurfaceFlinger 的时间轴,由它在对应 vsync 精确上屏 —— 不需要应用侧「来不及就跳过」。
 *     补帧后单帧延迟下限是 20ms(50fps,见 `RifeInterpolator.TARGET_MIN_DELAY_MS`),
 *     低于任何现代屏幕的刷新率,所以每一帧都能分到至少一个 vsync,**一帧都不丢**。
 *  3. **落后了拉时间轴,而不是丢帧**。真被系统卡住(GC / 冷启动抖动)时不追帧、不跳帧,
 *     而是把时间原点往后挪 [LATE_REBASE_MS],从当前帧继续按原速播完 —— 宁可整体晚一点,
 *     也不少给用户一帧。
 *
 * **无缝循环**:不发 EOS。读到文件尾就 `seekTo(0)` 并给后续 sample 的 PTS 加上一整圈
 * [loopDurationMs],解码器的时间轴保持连续、缓冲区不清空,循环接缝处没有卡顿或黑帧。
 * 一圈时长用调用方给的 [loopDurationMs](= `delays.txt` 之和)而不是容器 duration ——
 * mp4 不记最后一帧的显示时长,用容器 duration 会让循环早回卷一帧。
 *
 * 线程模型:一条解码线程包办 extractor/codec,主线程只收回调。[stop] 会 join 这条线程,
 * 保证调用方(TextureView 的 `onSurfaceTextureDestroyed`)返回时 Surface 已无人使用。
 */
class UgoiraVideoPlayer(
    private val videoFile: File,
    private val loopDurationMs: Int,
    /** 当前屏幕刷新率(Hz)。用来把送帧时刻对齐到刷新栅格,见 [VsyncClock]。 */
    private val refreshRateHz: () -> Float = { 60f },
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onFirstFrame: () -> Unit = {},
    private val onError: () -> Unit = {},
) {

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 落后超过这个值就重置时间原点(而不是丢帧追赶)。 */
        private const val LATE_REBASE_MS = 120L

        /** [stop] 等解码线程收尾的上限。正常几毫秒就退。 */
        private const val JOIN_TIMEOUT_MS = 800L

        /** 单次 park 的上限:睡再久也要定期回来看一眼 [running]。 */
        private const val MAX_PARK_NS = 20_000_000L

        /**
         * 对齐到刷新栅格后,提前多少纳秒把帧丢进队列。TextureView 是在 vsync 的绘制阶段
         * latch 最新可用帧,所以必须**赶在那次绘制之前**入队;但也不能太早,早过上一次
         * 绘制就会早显示一整帧。2ms 在 60Hz(16.7ms 间隔)下两头都够安全。
         */
        private const val VSYNC_GUARD_NS = 2_000_000L

        /** 卡死判定的下限:短动图一圈只有几百毫秒,不能拿一圈当阈值误杀。 */
        private const val STALL_MIN_MS = 3_000L
    }

    private val vsync = VsyncClock(refreshRateHz)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** 仅用于日志:送显了多少帧、循环了几圈、首帧时刻(算实际帧率,验证没有快进/丢帧)。 */
    @Volatile private var renderedCount = 0
    @Volatile private var loopCount = 0
    @Volatile private var firstFrameNanos = 0L

    /** 必须在主线程调用([VsyncClock] 要挂主线程的 Choreographer)。 */
    fun start(surface: Surface) {
        if (running) return
        running = true
        renderedCount = 0
        loopCount = 0
        vsync.start()
        thread = Thread({ decodeLoop(surface) }, "ugoira-mp4").apply { start() }
    }

    /**
     * 停止并释放解码器。
     *
     * @return true = 解码线程确实已退出,调用方可以安全 release Surface;false = join 超时,
     * 线程可能还在往 Surface 送帧 —— 此时调用方**不要** release 它(宁可漏一个 Surface 等
     * finalizer 收,也不要让 native 侧对着已销毁的 Surface 写)。
     */
    fun stop(): Boolean {
        if (!running && thread == null) return true
        running = false
        vsync.stop()
        var joined = true
        thread?.let { t ->
            LockSupport.unpark(t) // 正卡在 parkUntil 等下一帧时刻的话,立刻叫醒它收尾
            runCatching { t.join(JOIN_TIMEOUT_MS) }
            if (t.isAlive) {
                joined = false
                Timber.tag(UGOIRA_LOG_TAG).w("[mp4] 解码线程未在 %dms 内退出,不释放 Surface", JOIN_TIMEOUT_MS)
            }
        }
        thread = null
        if (renderedCount > 0) {
            // 实际帧率对不上「帧数 / 一圈时长」就是快进或拖慢了 —— 这行日志是回归哨兵。
            val elapsedMs = (System.nanoTime() - firstFrameNanos) / 1_000_000L
            Timber.tag(UGOIRA_LOG_TAG).i(
                "[mp4] 停止:送显 %d 帧,循环 %d 圈,历时 %dms(实测 %.1f fps)",
                renderedCount, loopCount, elapsedMs,
                if (elapsedMs > 0) renderedCount * 1000.0 / elapsedMs else 0.0,
            )
        }
        return joined
    }

    /**
     * 睡到 [targetNs](System.nanoTime 时基)。[running] 变 false 时提前醒,[stop] 才不用等
     * 一整帧;分段 park 是为了让这个检查有机会跑到。
     */
    private fun parkUntil(targetNs: Long) {
        while (running) {
            val remain = targetNs - System.nanoTime()
            if (remain <= 0L) return
            LockSupport.parkNanos(remain.coerceAtMost(MAX_PARK_NS))
        }
    }

    private fun decodeLoop(surface: Surface) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(videoFile.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            val fmt = format ?: throw IllegalStateException("no video track in $videoFile")
            ex.selectTrack(track)
            val width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            mainHandler.post { if (running) onVideoSize(width, height) }

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val dec = MediaCodec.createDecoderByType(mime).also { codec = it }
            dec.configure(fmt, surface, null, 0)
            dec.start()

            val loopUs = loopDurationMs.coerceAtLeast(1) * 1000L
            val info = MediaCodec.BufferInfo()
            var loopOffsetUs = 0L
            var inputDone = false
            var startNanos = 0L
            var firstFrame = true

            // 解码器卡死看门狗:一整圈(至少 3 秒)都没吐出一帧,就当这条 mp4 播不了 ——
            // 抛出去让 onError 把画面切回帧序列,而不是让用户对着预览图干等。正常等待
            // (parkUntil 到下一帧时刻)最多一个帧间隔,离这个阈值差着量级。
            val stallLimitNs = maxOf(STALL_MIN_MS, loopDurationMs.toLong()) * 1_000_000L
            var lastProgressNanos = System.nanoTime()

            while (running) {
                if (System.nanoTime() - lastProgressNanos > stallLimitNs) {
                    throw IllegalStateException("decoder produced no frame for ${stallLimitNs / 1_000_000}ms")
                }
                // ── 喂数据。读到尾就回卷:seek 回 0、PTS 整体加一圈,时间轴保持单调递增。
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        var size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            loopOffsetUs += loopUs
                            loopCount++
                            ex.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            size = ex.readSampleData(buf, 0)
                        }
                        if (size < 0) {
                            // 回卷后仍读不到:文件坏了,正常收尾走 EOS
                            inputDone = true
                            dec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                        } else {
                            dec.queueInputBuffer(
                                inIdx, 0, size, ex.sampleTime + loopOffsetUs, 0,
                            )
                            ex.advance()
                        }
                    }
                }

                // ── 取解码结果,按 PTS 排进合成器的时间轴
                val outIdx = dec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val render = info.size > 0
                        if (render) {
                            val ptsNs = info.presentationTimeUs * 1000L
                            val now = System.nanoTime()
                            if (firstFrame) {
                                // 首帧立刻上屏,并以它为时间原点
                                startNanos = now - ptsNs
                                firstFrame = false
                            }
                            var targetNs = startNanos + ptsNs
                            if (targetNs < now - LATE_REBASE_MS * 1_000_000L) {
                                // 被系统卡过:平移时间原点从当前帧继续,不丢帧追赶
                                startNanos = now - ptsNs
                                targetNs = now
                            }
                            // 吸附到刷新栅格(理由见 [VsyncClock]),再自己睡到点。
                            //
                            // **必须自己等**:TextureView 背后是 SurfaceTexture,它只在收到帧时
                            // 唤醒一次绘制、latch 最新的那张,**不认 releaseOutputBuffer 的目标
                            // 时间戳**(那个只对 SurfaceView 走的 SurfaceFlinger 生效)。不等就是
                            // 「解出来多快播多快」—— 解码器几百 fps,画面直接鬼畜。等待期间这张
                            // 输出缓冲一直被占着,天然给解码器背压,不会解爆内存。
                            val snappedNs = vsync.snapToGrid(targetNs)
                            parkUntil(snappedNs - VSYNC_GUARD_NS)
                            dec.releaseOutputBuffer(outIdx, snappedNs)
                            lastProgressNanos = System.nanoTime()
                            renderedCount++
                            if (renderedCount == 1) {
                                firstFrameNanos = System.nanoTime()
                                mainHandler.post { if (running) onFirstFrame() }
                            }
                        } else {
                            dec.releaseOutputBuffer(outIdx, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }

                    // 不用 dec.outputFormat 的宽高覆盖尺寸:那是**编码尺寸**(会被补齐到宏块
                    // 整数倍,如 500→512),显示尺寸在 crop 矩形里。extractor 的 track format
                    // 给的就是显示尺寸,而解码器输出到 Surface 时已按 crop 裁好,直接用它才对。
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[mp4] 解码失败,回退帧序列播放")
                mainHandler.post { if (running) onError() }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}

/**
 * vsync 栅格采样器:相位取自主线程 [Choreographer] 的 `frameTimeNanos`(它就是那一次刷新的
 * vsync 时刻),周期取自当前屏幕刷新率(自适应刷新率的机器会随模式切换而变,所以每帧重读)。
 *
 * 为什么需要它:屏幕只在 vsync 换画面,所以一帧的实际显示时长永远是刷新周期的整数倍。
 * 按「理想时刻」送帧时,相邻两帧哪怕只差 1ms 落在栅格两侧,显示时长就差一整个周期
 * (60Hz 上 16.7ms)。补帧把帧间隔压到 20~25ms 之后,这一下抖动占到 70%,画面就是
 * 「帧数翻倍但看着还是不匀」。[snapToGrid] 把目标时刻吸附到最近的栅格点,让同一速率的
 * 每一帧拿到稳定的 vsync 数(如 120Hz 上的 40fps 恒为 3 个),这才是补帧该有的观感。
 */
private class VsyncClock(private val refreshRateHz: () -> Float) {

    @Volatile
    private var lastVsyncNanos = 0L

    @Volatile
    private var periodNanos = DEFAULT_PERIOD_NS

    @Volatile
    private var running = false

    /** 上次采刷新率的时刻。相位每帧都要,但刷新率不必 —— 见 [RATE_SAMPLE_INTERVAL_NS]。 */
    private var lastRateSampleNanos = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            lastVsyncNanos = frameTimeNanos
            // 刷新率隔一段时间读一次就够:模式切换是秒级事件,而 Display.getRefreshRate()
            // 背后是 DisplayManagerGlobal 的缓存查询(可能落到 binder),不该每个 vsync 走一遍。
            if (frameTimeNanos - lastRateSampleNanos > RATE_SAMPLE_INTERVAL_NS) {
                lastRateSampleNanos = frameTimeNanos
                val hz = refreshRateHz()
                if (hz >= MIN_REFRESH_HZ) periodNanos = (1_000_000_000.0 / hz).toLong()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 主线程调用。 */
    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** 主线程调用。 */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /**
     * 把 [targetNs] 吸附到最近的一个 vsync(偏移不超过半个周期,所以不会改变播放速率,
     * 只是消掉相位抖动)。还没采到 vsync 时原样返回。
     */
    fun snapToGrid(targetNs: Long): Long {
        val vsync = lastVsyncNanos
        val period = periodNanos
        if (vsync == 0L || period <= 0L) return targetNs
        val k = Math.round((targetNs - vsync).toDouble() / period)
        return vsync + k * period
    }

    private companion object {
        const val DEFAULT_PERIOD_NS = 16_666_667L // 60Hz,拿到真实刷新率前的兜底
        const val MIN_REFRESH_HZ = 20f            // 明显不合理的刷新率读数不采信

        /** 刷新率的采样间隔:模式切换是秒级事件,500ms 一次足够跟上,又不占主线程。 */
        const val RATE_SAMPLE_INTERVAL_NS = 500_000_000L
    }
}
