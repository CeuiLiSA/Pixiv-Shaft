package ceui.pixiv.ui.detail.frames

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.v3Font
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 逐帧页的时间轴：剪辑软件那一套「固定播放头 + 底下的胶片滚动」。
 *
 * - 每一帧是一格，**格宽 = 这一帧的停留时长 × 缩放**，pixiv 动图的帧时长不等，长帧就是宽格，
 *   和剪映 / CapCut 的时间轴一样按时间铺开，而不是等宽的缩略图列表；
 *   格与格之间留 2dp 缝，读得出这是离散的帧，不是连续视频。
 * - 播放头固定在正中，拖动的是胶片；松手或惯性停下后吸附到当前帧的中心（Google 相册导出帧的手感），
 *   当前帧套一圈主题色选框（iOS 实况照片选封面帧的那只框）。
 * - 双指捏合缩放时间轴，播放头下的时刻不动；轻点某一格跳过去；长按某一格标记 / 取消标记。
 * - 用户拖动时每跨过一帧给一次 CLOCK_TICK 震动，像拨动滚轮。
 *
 * 只画和报手势，不认识播放、不认识文件；帧缩略图由 [thumbAt] 按需给，给不出就画占位底色。
 */
class UgoiraTimelineView(context: Context) : View(context) {

    interface Listener {
        /** 用户开始拖 / 捏 / 点：页面应暂停播放。 */
        fun onScrubStart()

        /** 播放头下的位置变了（毫秒，[fromUser] = 用户手势，而非 [setPosition]）。 */
        fun onPositionChanged(positionMs: Float, frameIndex: Int, fromUser: Boolean)

        fun onToggleMark(frameIndex: Int)
    }

    var listener: Listener? = null

    /** 帧缩略图；没解出来返回 null，画占位底。 */
    var thumbAt: (Int) -> Bitmap? = { null }

    private val palette = V3Palette.from(context)

    private var delays = IntArray(0)
    private var starts = LongArray(0)
    private var totalMs = 0L
    private var minDelay = 1
    private var marks: Set<Int> = emptySet()

    /** 播放头下的时刻，毫秒，区间 [0, totalMs)。 */
    var positionMs = 0f
        private set

    /** 时间轴缩放：每毫秒多少像素。默认让「典型帧」约 52dp 宽。 */
    private var pxPerMs = 0f
    private var minPxPerMs = 0f
    private var maxPxPerMs = 0f

    private var currentIndex = 0
    private var userActive = false

    // ── 尺寸 ────────────────────────────────────────────────────────
    private val markLaneH = context.dpF(16f)
    private val stripH = context.dpF(60f)
    private val rulerGap = context.dpF(8f)
    private val cellGap = context.dpF(2f)
    private val cellRadius = context.dpF(6f)
    private val selectInset = context.dpF(3f)
    private val selectStroke = context.dpF(2.5f)
    private val playheadW = context.dpF(2f)
    private val knobR = context.dpF(4f)
    private val markDotR = context.dpF(3f)
    private val edgeFadeW = context.dpF(28f)
    private val stripTop get() = markLaneH
    private val stripBottom get() = markLaneH + stripH

    // ── 画笔 ────────────────────────────────────────────────────────
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.alpha15 }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = selectStroke
        color = palette.textAccent
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.v3_text_1)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.textAccent }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.v3_text_3)
        strokeWidth = context.dpF(1f)
    }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.v3_text_2)
        typeface = context.v3Font(500)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    // onDraw 在播放时每个 vsync 都跑：字体度量与刻度文字缓存起来，不在绘制路径上分配。
    private val rulerMetrics = rulerText.fontMetrics
    private val rulerLabels = HashMap<Int, String>()
    private val fadeStart = Paint()
    private val fadeEnd = Paint()
    private val bgColor = context.color(R.color.v3_bg)

    private val cellRect = RectF()
    private val srcRect = Rect()
    private val clip = Path()

    // ── 手势 ────────────────────────────────────────────────────────
    private val scroller = OverScroller(context)
    private var flingLastX = 0
    private var snapAnimator: ValueAnimator? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopMotion()
            beginUserGesture()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            moveTo(positionMs + dx / pxPerMs, fromUser = true)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || totalMs <= 0) return false
            flingLastX = 0
            // 以像素为单位甩：startX = 0，边界按当前位置折算，停下后在 computeScroll 里吸附。
            val maxLeft = (-positionMs * pxPerMs).toInt()
            val maxRight = ((totalMs - 1 - positionMs) * pxPerMs).toInt()
            scroller.fling(0, 0, -vx.toInt(), 0, maxLeft, maxRight, 0, 0)
            ViewCompat.postInvalidateOnAnimation(this@UgoiraTimelineView)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val index = frameAtX(e.x) ?: return false
            snapTo(index, animate = true, fromUser = true)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            val index = frameAtX(e.x) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onToggleMark(index)
        }
    })

    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            stopMotion()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // 围绕播放头缩放：positionMs 不变，只改每毫秒像素数。
            pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(minPxPerMs, maxPxPerMs)
            invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        isFocusable = true
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ── 数据 ────────────────────────────────────────────────────────

    fun setFrames(frameDelays: List<Int>) {
        delays = frameDelays.map { it.coerceAtLeast(1) }.toIntArray()
        starts = LongArray(delays.size)
        var acc = 0L
        delays.forEachIndexed { i, d ->
            starts[i] = acc
            acc += d
        }
        totalMs = acc
        minDelay = delays.minOrNull() ?: 1
        rulerLabels.clear()
        val typical = delays.sorted().getOrElse(delays.size / 2) { 100 }.coerceAtLeast(1)
        pxPerMs = context.dpF(52f) / typical
        // 最窄：最短的帧也有 6dp，最宽：典型帧 180dp。
        minPxPerMs = context.dpF(6f) / (delays.minOrNull() ?: typical).coerceAtLeast(1)
        minPxPerMs = minOf(minPxPerMs, pxPerMs)
        maxPxPerMs = maxOf(context.dpF(180f) / typical, pxPerMs)
        currentIndex = 0
        positionMs = 0f
        invalidate()
    }

    fun setMarks(value: Set<Int>) {
        marks = value
        invalidate()
    }

    fun refreshThumbs() = postInvalidateOnAnimation()

    val frameCount: Int get() = delays.size

    fun startOf(index: Int): Long = starts.getOrElse(index) { 0L }

    fun indexAt(ms: Float): Int {
        if (delays.isEmpty()) return 0
        var lo = 0
        var hi = starts.size - 1
        val t = ms.toLong().coerceIn(0L, totalMs - 1)
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 播放驱动位置。用户正在拖时忽略，手势优先。 */
    fun setPosition(ms: Float) {
        if (userActive || delays.isEmpty()) return
        stopMotion()
        positionMs = ms.coerceIn(0f, (totalMs - 1).toFloat())
        currentIndex = indexAt(positionMs)
        postInvalidateOnAnimation()
    }

    /** 让播放头停在第 [index] 帧格子的中心。 */
    fun snapTo(index: Int, animate: Boolean, fromUser: Boolean) {
        if (delays.isEmpty()) return
        val i = index.coerceIn(0, delays.size - 1)
        val target = starts[i] + delays[i] / 2f
        snapAnimator?.cancel()
        if (!animate || !motionEnabled() || abs(target - positionMs) < 0.5f) {
            moveTo(target, fromUser)
            return
        }
        val from = positionMs
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { moveTo(from + (target - from) * it.animatedFraction, fromUser) }
            start()
        }
    }

    // ── 触摸 ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (delays.isEmpty() || !isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaling = false
                // 没甩出去（或只是轻点，轻点自己会吸附）：原地吸附到当前帧中心。
                if (scroller.isFinished && snapAnimator?.isRunning != true) snapTo(currentIndex, animate = true, fromUser = true)
                userActive = false
            }
        }
        return true
    }

    override fun computeScroll() {
        if (!scroller.isFinished && scroller.computeScrollOffset()) {
            val x = scroller.currX
            moveTo(positionMs + (x - flingLastX) / pxPerMs, fromUser = true)
            flingLastX = x
            if (scroller.isFinished) {
                snapTo(currentIndex, animate = true, fromUser = true)
            } else {
                ViewCompat.postInvalidateOnAnimation(this)
            }
        }
    }

    private fun beginUserGesture() {
        if (!userActive) {
            userActive = true
            listener?.onScrubStart()
        }
    }

    private fun stopMotion() {
        if (!scroller.isFinished) scroller.forceFinished(true)
        snapAnimator?.cancel()
    }

    private fun moveTo(ms: Float, fromUser: Boolean) {
        if (delays.isEmpty()) return
        positionMs = ms.coerceIn(0f, (totalMs - 1).toFloat())
        val index = indexAt(positionMs)
        if (index != currentIndex) {
            currentIndex = index
            if (fromUser) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        listener?.onPositionChanged(positionMs, currentIndex, fromUser)
        postInvalidateOnAnimation()
    }

    private fun frameAtX(x: Float): Int? {
        if (delays.isEmpty()) return null
        val ms = positionMs + (x - width / 2f) / pxPerMs
        if (ms < 0 || ms >= totalMs) return null
        return indexAt(ms)
    }

    // ── 键盘与读屏 ──────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        listener?.onScrubStart()
        snapTo(currentIndex + step, animate = true, fromUser = true)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val compat = AccessibilityNodeInfoCompat.wrap(info)
        compat.className = "android.widget.SeekBar"
        if (delays.isEmpty()) return
        compat.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
            AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT, 1f, delays.size.toFloat(), currentIndex + 1f,
        )
        compat.stateDescription = context.getString(R.string.ugoira_frames_counter, currentIndex + 1, delays.size)
        if (currentIndex > 0) compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
        if (currentIndex < delays.size - 1) compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        listener?.onScrubStart()
        snapTo(currentIndex + step, animate = false, fromUser = true)
        return true
    }

    // ── 测量与绘制 ──────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = rulerMetrics
        val h = stripBottom + rulerGap + context.dpF(6f) + (fm.descent - fm.ascent) + context.dpF(4f)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(h.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 两端渐隐到页面底色：胶片从屏幕边「滑进来」，而不是被硬切。
        val transparent = bgColor and 0x00FFFFFF
        fadeStart.shader = LinearGradient(0f, 0f, edgeFadeW, 0f, bgColor, transparent, Shader.TileMode.CLAMP)
        fadeEnd.shader = LinearGradient(w - edgeFadeW, 0f, w.toFloat(), 0f, transparent, bgColor, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (delays.isEmpty()) return
        val cx = width / 2f
        val first = frameAtXClamped(0f)
        val last = frameAtXClamped(width.toFloat())
        val gap = if (minDelay * pxPerMs > cellGap * 4) cellGap else 0f

        // 胶片格
        for (i in first..last) {
            val left = cx + (starts[i] - positionMs) * pxPerMs
            val right = left + delays[i] * pxPerMs
            cellRect.set(left + gap / 2, stripTop, right - gap / 2, stripBottom)
            if (cellRect.width() <= 0f) continue
            clip.reset()
            clip.addRoundRect(cellRect, cellRadius, cellRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val thumb = thumbAt(i)
            if (thumb == null || thumb.isRecycled) {
                canvas.drawRect(cellRect, cellFill)
            } else {
                centerCrop(thumb, cellRect)
                canvas.drawBitmap(thumb, srcRect, cellRect, bitmapPaint)
            }
            canvas.restore()
            if (i in marks) {
                canvas.drawCircle(cellRect.centerX(), markLaneH / 2f, markDotR, markPaint)
            }
        }

        // 当前帧选框
        val cl = cx + (starts[currentIndex] - positionMs) * pxPerMs
        cellRect.set(cl + gap / 2 - selectInset, stripTop - selectInset, cl + delays[currentIndex] * pxPerMs - gap / 2 + selectInset, stripBottom + selectInset)
        canvas.drawRoundRect(cellRect, cellRadius + selectInset, cellRadius + selectInset, selectPaint)

        drawRuler(canvas, cx)
        drawEdgeFades(canvas)

        // 播放头：一条细线 + 顶端圆点，压在选框之上。
        canvas.drawRoundRect(cx - playheadW / 2, knobR, cx + playheadW / 2, stripBottom + selectInset * 2, playheadW, playheadW, playheadPaint)
        canvas.drawCircle(cx, knobR, knobR, playheadPaint)
    }

    private fun frameAtXClamped(x: Float): Int {
        val ms = positionMs + (x - width / 2f) / pxPerMs
        return indexAt(ms.coerceIn(0f, (totalMs - 1).toFloat()))
    }

    private fun centerCrop(bmp: Bitmap, dst: RectF) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val dstRatio = dst.width() / dst.height()
        if (bw / bh > dstRatio) {
            val w = bh * dstRatio
            val l = ((bw - w) / 2).toInt()
            srcRect.set(l, 0, (l + w).toInt(), bmp.height)
        } else {
            val h = bw / dstRatio
            val t = ((bh - h) / 2).toInt()
            srcRect.set(0, t, bmp.width, (t + h).toInt())
        }
    }

    private fun drawRuler(canvas: Canvas, cx: Float) {
        // 主刻度间隔：至少 64dp 一格，从常见的时间步长里挑最小的那个。
        val minSpacing = context.dpF(64f)
        val major = RULER_STEPS.firstOrNull { it * pxPerMs >= minSpacing } ?: RULER_STEPS.last()
        val minor = major / 5f
        val tickTop = stripBottom + rulerGap
        val startMs = ((positionMs - cx / pxPerMs) / minor).toInt().coerceAtLeast(0) * minor
        val endMs = minOf(totalMs.toFloat(), positionMs + (width - cx) / pxPerMs)
        val textY = tickTop + context.dpF(6f) - rulerMetrics.ascent + context.dpF(2f)
        var t = startMs
        var n = (startMs / minor).roundToInt()
        while (t <= endMs) {
            val x = cx + (t - positionMs) * pxPerMs
            if (n % 5 == 0) {
                canvas.drawLine(x, tickTop, x, tickTop + context.dpF(5f), tickPaint)
                val ms = t.roundToInt()
                canvas.drawText(rulerLabels.getOrPut(ms) { formatRuler(ms) }, x, textY, rulerText)
            } else {
                canvas.drawLine(x, tickTop, x, tickTop + context.dpF(2.5f), tickPaint)
            }
            n++
            t = n * minor
        }
    }

    private fun drawEdgeFades(canvas: Canvas) {
        canvas.drawRect(0f, 0f, edgeFadeW, height.toFloat(), fadeStart)
        canvas.drawRect(width - edgeFadeW, 0f, width.toFloat(), height.toFloat(), fadeEnd)
    }

    private fun formatRuler(ms: Int): String =
        if (ms % 1000 == 0) "${ms / 1000}s" else String.format(java.util.Locale.US, "%.1fs", ms / 1000f)

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    private companion object {
        val RULER_STEPS = floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 30000f)
    }
}
