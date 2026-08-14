package ceui.lisa.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.RelativeLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * 大图查看页根布局:接管「图片已到顶/底」状态下的单指继续外拉,让内容视图跟手位移/缩小,
 * 松手后由宿主决定收掉页面还是回弹 —— 小红书式全屏弹窗手势的手势层。
 *
 * 只做手势跟踪与跟手变换,不做动画:进场/回弹/收场动画统一归宿主(ImageViewerTransition),
 * 避免两处同时驱动同一组 view 属性打架。
 *
 * 与 ZoomImage 的配合:SketchZoomImageView 在 ACTION_DOWN 时 requestDisallowInterceptTouchEvent(true)。
 * 本布局从 dispatchTouchEvent 旁观完整事件流，只在图片已到边界时清掉该禁令并接管，
 * 因此可以在同一手势中从图片平移无缝转为整页收起，又不会抢缩放/长图滚动/翻页手势。
 */
class DragDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    enum class Direction(val sign: Float) {
        UP(-1f),
        DOWN(1f),
    }

    interface Callback {
        /** 此刻是否允许沿 [direction] 开始拖拽关闭。 */
        fun canStartDismissDrag(direction: Direction): Boolean

        /** 拖拽进行中。[fraction] 0=原位、1=拖满,宿主用它驱动黑底/工具条透明度。 */
        fun onDismissDragUpdate(fraction: Float)

        /** 松手。[shouldDismiss] true=越过距离/速度阈值要求收掉页面,false=请求回弹。 */
        fun onDismissDragRelease(shouldDismiss: Boolean, direction: Direction, velocityY: Float)
    }

    var callback: Callback? = null

    /** 跟手位移/缩放施加到的内容视图(= ViewPager)。 */
    var dragTargetView: View? = null

    /** 进场/回弹/收场动画期间置 true,禁止手势插一脚。 */
    var dragSuspended = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    private var gestureRejected = false
    private var consumeUntilGestureEnd = false
    private var dragDirection = Direction.DOWN

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 上一次被多指取消的序列理论上会以 UP/CANCEL 收尾，
            // 这里再兜底复位，避免异常事件流把下一次手势也吞掉。
            consumeUntilGestureEnd = false
        } else if (consumeUntilGestureEnd) {
            // 父布局已经接管后不能在同一序列中把事件还给收过 CANCEL
            // 的 ZoomImage，因此取消拖拽后吞到本次手势结束。
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                consumeUntilGestureEnd = false
            }
            return true
        }

        // ZoomImage 从 ACTION_DOWN 起会禁止祖先拦截，因此所有需要
        // 旁观完整事件流的判定都放在 dispatchTouchEvent。
        if (ev.pointerCount > 1) {
            gestureRejected = true
            if (dragging) {
                // 父布局已接管后再落下第二指：立即回弹，不再用可变的
                // pointer index 0 计算位移/速度，避免主指抬起后图片跳变甚至误关闭。
                consumeUntilGestureEnd = true
                endDrag(shouldDismiss = false, velocityY = 0f)
                return true
            }
        } else if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            releaseChildInterceptAtVerticalEdge(ev)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // 子 view 禁止拦截时，onInterceptTouchEvent 看不到收尾事件。
            // 在分发出口统一回收，避免非关闭手势把 tracker 留到下次 DOWN。
            velocityTracker?.recycle()
            velocityTracker = null
        }
        return handled
    }

    /**
     * ZoomImage 1.4.0 只在单指拖动首次越过 slop 时检查能否滚动；
     * 如果手势从图片中部开始，它到边界后不会再主动放行父布局。
     * 本布局在分发入口持续旁观：同一手势到顶/底后主动清掉
     * requestDisallowInterceptTouchEvent，让当前 MOVE 立即进入 onInterceptTouchEvent。
     */
    private fun releaseChildInterceptAtVerticalEdge(ev: MotionEvent) {
        if (dragging || gestureRejected || dragSuspended) return
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            // 手势一旦明确为横向，整个序列留给 ZoomImage / ViewPager。
            gestureRejected = true
            return
        }
        if (abs(dy) <= touchSlop || abs(dy) <= abs(dx)) return
        val direction = if (dy > 0f) Direction.DOWN else Direction.UP
        if (callback?.canStartDismissDrag(direction) == true) {
            // 在自身上调用才会同时清除本 ViewGroup 的
            // FLAG_DISALLOW_INTERCEPT，只通知 parent 不足以让 super.dispatchTouchEvent 重新询问拦截。
            requestDisallowInterceptTouchEvent(false)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                gestureRejected = dragSuspended
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                if (gestureRejected || ev.pointerCount > 1) return false
                velocityTracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    // 横向优先给 ViewPager 翻页,本次手势不再考虑竖向关闭
                    gestureRejected = true
                    return false
                }
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    val direction = if (dy > 0f) Direction.DOWN else Direction.UP
                    if (callback?.canStartDismissDrag(direction) != true) {
                        gestureRejected = true
                        return false
                    }
                    dragging = true
                    dragDirection = direction
                    // 从拦截点起算位移,避免起手瞬间跳过一个 slop 的距离
                    dragStartX = ev.x
                    dragStartY = ev.y
                    // 到边界前可能已在 ZoomImage 里平移了很久；关闭速度只应
                    // 从父布局真正接管的时刻起算，不混入前半段手势。
                    velocityTracker?.clear()
                    velocityTracker?.addMovement(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyDrag(ev.x - dragStartX, ev.y - dragStartY)
            MotionEvent.ACTION_UP -> {
                val vt = velocityTracker
                vt?.computeCurrentVelocity(1000)
                val velocityY = vt?.yVelocity ?: 0f
                val dy = ev.y - dragStartY
                val directedDistance = dy * dragDirection.sign
                val directedVelocity = velocityY * dragDirection.sign
                val flingThreshold = FLING_DISMISS_VELOCITY_DP * resources.displayMetrics.density
                val shouldDismiss = directedDistance > 0f &&
                        (directedDistance > height * DISMISS_DISTANCE_FRACTION ||
                                directedVelocity > flingThreshold)
                endDrag(shouldDismiss, velocityY)
            }
            MotionEvent.ACTION_CANCEL -> endDrag(shouldDismiss = false, velocityY = 0f)
        }
        return true
    }

    private fun endDrag(shouldDismiss: Boolean, velocityY: Float) {
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
        callback?.onDismissDragRelease(shouldDismiss, dragDirection, velocityY)
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val target = dragTargetView ?: return
        val directedDistance = dy * dragDirection.sign
        // 拦截后反向拖过原点时只给轻微阻尼，不触发缩小/关闭。
        val ty = if (directedDistance >= 0f) dy else dy * 0.2f
        val fraction = (max(0f, directedDistance) / (height * 0.4f)).coerceIn(0f, 1f)
        val scale = 1f - MAX_DRAG_SCALE_SHRINK * fraction
        target.translationX = dx
        target.translationY = ty
        target.scaleX = scale
        target.scaleY = scale
        callback?.onDismissDragUpdate(fraction)
    }

    companion object {
        /** 松手判定收掉的拖拽距离阈值(相对本布局高度)。 */
        private const val DISMISS_DISTANCE_FRACTION = 0.18f

        /** 快速外甩判定收掉的速度阈值,单位 dp/s。 */
        private const val FLING_DISMISS_VELOCITY_DP = 1200f

        /** 拖满时内容缩小到 1 - 0.3 = 0.7 倍。 */
        private const val MAX_DRAG_SCALE_SHRINK = 0.3f
    }
}
