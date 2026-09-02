package ceui.lisa.view

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * 一页全景图的横向偏移:0 = 靠左边缘,1 = 靠右边缘,默认 0.5 居中(与 FIT_CENTER 看到的是同一段)。
 * 由 adapter 按页持有,回收重绑后拖到哪还是哪;底层 large 与顶层原图 overlay 共用同一个实例。
 */
class PanoramaPan(
    fraction: Float = 0.5f,
) {
    var fraction: Float = fraction.coerceIn(0f, 1f)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }
}

/**
 * 挂在页 itemView 上的横向拖动 + 惯性。
 *
 * 方向判定由本监听器自己做,**按下时就先向祖先要 disallowIntercept**:RecyclerView 的拦截只看
 * 竖直位移过没过 slop、不比较横竖,快划时手指自带的竖向抖动先过阈值,事件就被它抢走,我们连
 * 第二个 MOVE 都收不到(表现为「快划不动、按住慢拖才走」)。抢先按住后:竖直位移先过 slop 且大于
 * 水平位移 → 把 disallow 还回去,RecyclerView 在下一个 MOVE 里照常从 DOWN 的起点算位移接管滚动;
 * 水平位移先过 → 我们接管,并给 itemView 补一个 CANCEL 把按下态和排队中的长按一起撤掉,否则拖到
 * 400ms 会触发「长按下载」。点按 / 长按不动:两个方向都没过 slop 时事件一律放行给 itemView。
 *
 * 抬手带速度就交给 [OverScroller] 减速滑到停(与 RecyclerView 同一套物理),再次按下即中止。
 * 拖动/惯性只改 [pan] 的 fraction,矩阵重算交给 [views]:每层 ImageView 各自按内容区算比例,
 * 底层 large(600px)与顶层原图分辨率不同也能对到同一段画面。
 */
class PanoramaDragListener(
    private val pan: PanoramaPan,
    private val views: List<DynamicHeightImageView>,
) : View.OnTouchListener {

    private val config = ViewConfiguration.get(views.first().context)
    private val touchSlop = config.scaledTouchSlop
    private val minFlingVelocity = config.scaledMinimumFlingVelocity
    private val maxFlingVelocity = config.scaledMaximumFlingVelocity
    private val scroller = OverScroller(views.first().context)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    /** 本次手势已经判为竖向、交还给父级;之后的 MOVE 一律放行,不再重复判定。 */
    private var yieldedToParent = false
    /**
     * 驱动拖动的那根手指。用户在全景图上试图双指缩放是常事:第二指按下不接管;第一指先抬时
     * 换到剩下的手指并重置基准点,否则 MOVE 里的 x 会突然变成另一根手指的位置,画面跳一段。
     */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    /**
     * 这段手势整体放行、不接管。第一指已经按在别的 view 上、第二指才落到全景图时,ViewGroup 会把
     * 那个 POINTER_DOWN 拆成一个 DOWN 发给我们(eventTime 晚于 downTime,真 DOWN 两者相等)。
     * 此时祖先(ViewPager)的指针记账基于第一指;我们一旦 disallow,它就看不到第一指抬起,之后任何
     * 交还都会让它拿旧指针 id 取坐标而崩(ViewPager 1.0 不校验 findPointerIndex 的 -1)。
     */
    private var ignoreGesture = false

    /** 惯性按帧推进:偏移以像素记在 scroller 里,换算回 fraction 喂给各层。 */
    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val maxShift = maxShift()
            // 页被回收滚出窗口:postOnAnimation 对未 attach 的 view 会排队到下次 attach 再跑,
            // 那时 view 可能已经绑了别的页,直接停掉。
            if (maxShift <= 0f || !views.first().isAttachedToWindow) {
                scroller.abortAnimation()
                return
            }
            setFraction(scroller.currX / maxShift)
            views.first().postOnAnimation(this)
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 惯性中按下 = 按住画面,先停下来
                if (!scroller.isFinished) scroller.abortAnimation()
                ignoreGesture = event.eventTime != event.downTime
                if (ignoreGesture) return false
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                yieldedToParent = false
                if (maxShift() > 0f) v.parent?.requestDisallowInterceptTouchEvent(true)
                velocityTracker?.clear()
                velocityTracker = (velocityTracker ?: VelocityTracker.obtain()).also { it.addMovement(event) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreGesture) return false
                velocityTracker?.addMovement(event)
                val maxShift = maxShift()
                if (maxShift <= 0f) return false
                if (yieldedToParent) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return dragging
                val x = event.getX(index)
                if (!dragging) {
                    val dx = x - downX
                    val dy = event.getY(index) - downY
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        // 竖向手势:还给 RecyclerView 滚列表
                        yieldedToParent = true
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    if (abs(dx) <= touchSlop || abs(dx) <= abs(dy)) {
                        return false
                    }
                    dragging = true
                    cancelPendingPress(v, event)
                    lastX = x
                }
                val delta = x - lastX
                lastX = x
                setFraction(pan.fraction - delta / maxShift)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ignoreGesture) return false
                val leaving = event.actionIndex
                if (event.getPointerId(leaving) == activePointerId) {
                    val next = if (leaving == 0) 1 else 0
                    activePointerId = event.getPointerId(next)
                    lastX = event.getX(next)
                    downX = lastX
                    downY = event.getY(next)
                    velocityTracker?.clear()
                    if (!yieldedToParent && !dragging) {
                        // 换指之后这个手势不能再交还父级:父级(ViewPager 1.0 的 onInterceptTouchEvent)
                        // 被 disallow 挡着没看到这次 POINTER_UP,仍拿旧指针 id 找索引,findPointerIndex
                        // 返回 -1 它不校验就 getX,直接 IllegalArgumentException(真机复现过)。
                        // 直接按横向拖动接管,剩下的手指继续拖全景。
                        dragging = true
                        cancelPendingPress(v, event)
                    }
                }
                return dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ignoreGesture) return false
                val tracker = velocityTracker
                velocityTracker = null
                if (!dragging) {
                    tracker?.recycle()
                    return false
                }
                dragging = false
                // 刻意不在这里把 disallow 还回去:下一个 ACTION_DOWN 每层 ViewGroup 都会自己重置。
                // 若此刻还有别的手指按在别的条目上(本 UP 是拆分出来的),现在交还等于让 ViewPager
                // 用它从未见过抬起的旧指针去取坐标 → 崩;那根手指剩下的手势就让它无法滚动,直到抬起。
                if (event.actionMasked == MotionEvent.ACTION_UP && tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    startFling(tracker.getXVelocity(activePointerId))
                }
                tracker?.recycle()
                return true
            }
        }
        return false
    }

    private fun startFling(velocityX: Float) {
        if (abs(velocityX) < minFlingVelocity) return
        val maxShift = maxShift()
        if (maxShift <= 0f) return
        // 手指向左划(速度为负)= 画面向左走 = 偏移增大,所以速度取反
        scroller.fling(
            (pan.fraction * maxShift).toInt(), 0,
            (-velocityX).toInt(), 0,
            0, maxShift.toInt(),
            0, 0,
        )
        views.first().postOnAnimation(flingStep)
    }

    /** 取各层里最大的可拖范围:原图模式下底层 large 可能根本没图(多 P 不发 large 请求),只有顶层有位图。 */
    private fun maxShift(): Float = views.maxOf { it.panoramaMaxShift }

    private fun setFraction(next: Float) {
        val clamped = next.coerceIn(0f, 1f)
        if (clamped == pan.fraction) return
        pan.fraction = clamped
        views.forEach { it.applyPanoramaMatrix() }
    }

    /** 直接喂给 itemView 自己的 onTouchEvent(不走 dispatch,免得又绕回本监听器)。 */
    private fun cancelPendingPress(v: View, source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        v.onTouchEvent(cancel)
        cancel.recycle()
    }
}
