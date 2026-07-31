package ceui.pixiv.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import timber.log.Timber
import java.io.File
import java.util.ArrayDeque

/**
 * Ugoira 帧序列播放器 —— 取代「编成 GIF 交给 Glide 播」的老路。
 *
 * 老路为什么不行(真机实测,Pixel 8 / 490x445 / 280 帧):Glide 的 `StandardGifDecoder` 是
 * **纯 Java 软解 LZW**,单帧约 25ms;而 4x 补帧把每帧预算压到 20ms —— 预算小于解码成本,
 * 每帧透支 5ms。更要命的是 Glide 的调度是 `下一帧 = 现在 + delay` 的**相对时间轴**,
 * 晚了不会追,误差直接累积:一圈 5.6s 播成 7.0s;而持续满载解码又把机器烤到降频,
 * 解码涨到 33~52ms,一圈变 9~15s —— 越看越慢。
 *
 * 本播放器换成播放器该有的两条规矩:
 *
 *  1. **绝对时间轴**:每次 vsync 用「开播至今的真实耗时」反查当前该显示第几帧
 *     ([frameIndexAt]),而不是把 delay 一帧帧加起来。任何一帧慢了都不会污染后续时刻,
 *     一圈的时长永远等于 [totalMs]。
 *  2. **跟不上就丢帧**:解码线程同样按时间轴取要解的帧号,解得慢时帧号自然跳跃,中间那些
 *     帧根本不解码。牺牲的是流畅度,**不是动作速率** —— 这正是所有视频播放器的做法。
 *
 * 另外帧源是 JPEG,走 `BitmapFactory` 的 native 解码(libjpeg),比 GIF 的 Java 软解快数倍,
 * 20ms 的预算下有充足余量;内存也从 Glide 的「整个 22MB 文件常驻 + 帧缓存」降到 3 张
 * Bitmap([POOL_SIZE] 轮转复用,约 2.6MB)。
 *
 * 线程模型:一条解码线程 + 主线程 [Choreographer]。二者只通过 [lock] 交接一个 [pending]
 * 槽位 —— 主线程从不碰文件系统,解码线程从不碰 View。
 */
class FrameSequencePlayer(
    private val files: List<File>,
    delaysMs: List<Int>,
    private val onFrame: (Bitmap) -> Unit,
) {

    companion object {
        /** Bitmap 轮转池大小:正在显示 1 张 + 待取 1 张 + 正在解 1 张。 */
        private const val POOL_SIZE = 3

        /**
         * 解码线程比显示时刻提前多少毫秒取帧号。太小则解出来就已经过期(白解一张),
         * 太大则画面比音画时间轴超前。取一帧典型解码耗时的量级即可。
         */
        private const val LOOKAHEAD_MS = 12L

        /** 帧延迟下限,防御 metadata 给 0 导致 [totalMs] 为 0 / 除零。 */
        private const val MIN_DELAY_MS = 10
    }

    /** ends[i] = 第 i 帧**结束**时刻(从开播算起的累积毫秒)。用于二分反查帧号。 */
    private val ends = LongArray(files.size)

    /** 一圈总时长。播放位置 = elapsed % totalMs,天然无限循环。 */
    private val totalMs: Long

    init {
        var acc = 0L
        for (i in files.indices) {
            acc += delaysMs.getOrElse(i) { 60 }.coerceAtLeast(MIN_DELAY_MS).toLong()
            ends[i] = acc
        }
        totalMs = acc
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 解码线程放、主线程取的单槽。主线程没来得及取就被下一帧覆盖 —— 这就是丢帧。 */
    private var pending: Bitmap? = null
    private var pendingIndex = -1

    /** 可复用的 Bitmap(inBitmap),由主线程用完归还。 */
    private val freePool = ArrayDeque<Bitmap>(POOL_SIZE)
    private var displayed: Bitmap? = null

    @Volatile
    private var running = false
    private var startNanos = 0L
    private var decodeThread: Thread? = null

    // 统计:仅用于日志,判断丢了多少帧
    @Volatile private var decodedCount = 0
    @Volatile private var shownCount = 0
    @Volatile private var skippedCount = 0

    val frameCount: Int get() = files.size

    /** 当前时间轴位置对应第几帧。二分找第一个 ends[i] > pos 的 i。 */
    private fun frameIndexAt(elapsedMs: Long): Int {
        if (totalMs <= 0L || ends.isEmpty()) return 0
        val pos = elapsedMs % totalMs
        var lo = 0
        var hi = ends.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pos < ends[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    fun start() {
        if (running || files.isEmpty()) return
        running = true
        startNanos = System.nanoTime()
        decodedCount = 0; shownCount = 0; skippedCount = 0
        decodeThread = Thread({ decodeLoop() }, "ugoira-frames").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
        Timber.tag(UGOIRA_LOG_TAG)
            .i("[frames] 开始播放 %d 帧,一圈 %dms", files.size, totalMs)
    }

    /** 停止并释放所有 Bitmap。可重复调用;stop 后还能再 [start]。 */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        decodeThread?.interrupt()
        decodeThread = null
        synchronized(lock) {
            pending?.recycle()
            pending = null
            pendingIndex = -1
            while (freePool.isNotEmpty()) freePool.poll()?.recycle()
        }
        // displayed 还挂在 ImageView 上,不能在这里 recycle;交给调用方 clear 后 GC。
        displayed = null
        if (shownCount > 0) {
            Timber.tag(UGOIRA_LOG_TAG).i(
                "[frames] 停止:解码 %d 帧,显示 %d 帧,按时间轴跳过 %d 帧",
                decodedCount, shownCount, skippedCount,
            )
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val next: Bitmap?
            synchronized(lock) {
                next = pending
                pending = null
            }
            if (next != null) {
                val old = displayed
                displayed = next
                onFrame(next)
                shownCount++
                // 上一张已经不在屏幕上了(ImageView 已换图),归还池子给解码线程复用
                if (old != null) synchronized(lock) {
                    if (freePool.size < POOL_SIZE) freePool.offer(old) else old.recycle()
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun decodeLoop() {
        var lastIndex = -1
        val opts = BitmapFactory.Options()
        while (running) {
            try {
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
                val want = frameIndexAt(elapsed + LOOKAHEAD_MS)
                if (want == lastIndex) {
                    Thread.sleep(2)
                    continue
                }
                // 帧号跳跃 = 解码没跟上时间轴,中间那些帧直接不解(丢帧保时长)
                if (lastIndex >= 0) {
                    val gap = (want - lastIndex + files.size) % files.size
                    if (gap > 1) skippedCount += gap - 1
                }
                val reuse = synchronized(lock) { freePool.poll() }
                opts.inMutable = true
                opts.inBitmap = reuse
                val bmp = try {
                    BitmapFactory.decodeFile(files[want].absolutePath, opts)
                } catch (t: IllegalArgumentException) {
                    // inBitmap 尺寸/格式不匹配(理论上不会,帧尺寸一致):退回不复用
                    opts.inBitmap = null
                    reuse?.recycle()
                    BitmapFactory.decodeFile(files[want].absolutePath, opts)
                }
                if (bmp == null) {
                    lastIndex = want
                    continue
                }
                decodedCount++
                lastIndex = want
                synchronized(lock) {
                    // 主线程还没取走上一张 → 它已经过期了,直接回收(这也是丢帧)
                    pending?.let { if (it !== bmp) it.recycle() }
                    pending = bmp
                    pendingIndex = want
                }
            } catch (i: InterruptedException) {
                return
            } catch (t: Throwable) {
                Timber.tag(UGOIRA_LOG_TAG).w(t, "[frames] 解码线程异常,停止播放")
                return
            }
        }
    }
}
