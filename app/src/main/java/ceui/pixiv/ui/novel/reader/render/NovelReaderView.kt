package ceui.pixiv.ui.novel.reader.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import timber.log.Timber
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.flip.CoverFlipAnimator
import ceui.pixiv.ui.novel.reader.render.flip.FlipAnimator
import ceui.pixiv.ui.novel.reader.render.flip.FlipAnimatorFactory
import ceui.pixiv.ui.novel.reader.render.flip.FlipDirection
import kotlin.math.abs

/**
 * Root container for the V3 reader. Owns three [PageView]s (previous / current /
 * next), handles touch (tap-zones, drag-to-flip, long-press for selection,
 * double-tap for image zoom forwarding), and drives page transitions through a
 * swappable [FlipAnimator].
 *
 * This view is agnostic of paginator / settings / data source plumbing. Feed it
 * a `List<Page>` + style and it will present them; call [setFlipMode] to change
 * animation style live.
 */
class NovelReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val prevView: PageView
    private val currentView: PageView
    private val nextView: PageView

    private var pages: List<Page> = emptyList()
    private var currentIndex: Int = 0
    private var style: TypeStyle? = null
    private var geometry: PageGeometry? = null
    private var bitmapSource: ImageBitmapSource = ImageBitmapSource.EMPTY
    private var overlays: PageOverlays = PageOverlays.EMPTY
    private var backgroundBitmap: android.graphics.Bitmap? = null

    private var flipMode: FlipMode = FlipMode.Slide
    private var animator: FlipAnimator = FlipAnimatorFactory.create(flipMode)

    // Drag state
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDownTime = 0L
    private var isDragging = false
    private var dragProgress = 0f
    private var dragDirection: FlipDirection = FlipDirection.Forward
    private var velocityTracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapMaxDurationMs = 220L
    private val tapMaxDistancePx = slop * 1.25f
    private val doubleTapMaxMs = 260L

    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var settleAnimator: ValueAnimator? = null
    private var touchLocked: Boolean = false

    // Listeners
    var onTapCenter: (() -> Unit)? = null
    var onPageChanged: ((Int) -> Unit)? = null
    var onDoubleTapAt: ((x: Float, y: Float) -> Unit)? = null
    var onEdgeHit: ((FlipDirection) -> Unit)? = null

    init {
        setWillNotDraw(false)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        prevView = PageView(context).also { addView(it, lp) }
        currentView = PageView(context).also { addView(it, lp) }
        nextView = PageView(context).also { addView(it, lp) }
        // Route bare text-taps (TextView consumed the UP but produced no
        // selection state change) through the reader's tap-zone logic so
        // flip-on-tap still works over text areas.
        for (pv in arrayOf(prevView, currentView, nextView)) {
            pv.onBlockBareTap = { x, y -> handleTap(x, y) }
        }
    }

    // ---- Public API --------------------------------------------------------

    fun setStyle(style: TypeStyle, geometry: PageGeometry) {
        this.style = style
        this.geometry = geometry
        refreshPages()
    }

    fun setBitmapSource(source: ImageBitmapSource) {
        this.bitmapSource = source
        prevView.updateBitmapSource(source)
        currentView.updateBitmapSource(source)
        nextView.updateBitmapSource(source)
    }

    fun setOverlays(overlays: PageOverlays) {
        this.overlays = overlays
        currentView.updateOverlays(overlays)
    }

    fun setBackgroundBitmap(bitmap: android.graphics.Bitmap?) {
        this.backgroundBitmap = bitmap
        refreshPages()
    }

    fun setFlipMode(mode: FlipMode) {
        if (mode == flipMode) return
        cancelSettle()
        animator.onReset(prevView, currentView, nextView)
        flipMode = mode
        animator = FlipAnimatorFactory.create(mode)
        dragProgress = 0f
        isDragging = false
    }

    /**
     * Wire the platform-selection callbacks and menu used by every
     * [PageView]'s [ReaderTextBlockView] children. Call once from the host
     * fragment — the view propagates to all three pooled pages.
     */
    fun setTextBlockSelectionHandlers(
        onStart: ((ReaderTextBlockView, Int, Int, CharSequence) -> Unit)? = null,
        onChange: ((ReaderTextBlockView, Int, Int, CharSequence) -> Unit)? = null,
        onEnd: ((ReaderTextBlockView) -> Unit)? = null,
        menuEntries: List<ReaderTextBlockView.MenuEntry> = emptyList(),
        onMenuAction: ((Int) -> Unit)? = null,
    ) {
        for (pv in arrayOf(prevView, currentView, nextView)) {
            pv.onBlockSelectionStart = onStart
            pv.onBlockSelectionChange = onChange
            pv.onBlockSelectionEnd = onEnd
            pv.blockMenuEntries = menuEntries
            pv.onBlockMenuAction = onMenuAction
        }
        refreshPages()
    }

    fun setTouchLocked(locked: Boolean) {
        touchLocked = locked
        if (locked) cancelAllGestures()
    }

    fun bind(pages: List<Page>, initialIndex: Int = 0) {
        Timber.tag(TAG).d("bind() size=${pages.size} initialIndex=$initialIndex (prev currentIndex=$currentIndex, isDragging=$isDragging, settling=${settleAnimator?.isRunning == true})")
        this.pages = pages
        this.currentIndex = initialIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        refreshPages()
        onPageChanged?.invoke(currentIndex)
    }

    fun goToPage(index: Int, animate: Boolean = false) {
        val bounded = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (bounded == currentIndex) return
        if (!animate || pages.isEmpty()) {
            currentIndex = bounded
            refreshPages()
            onPageChanged?.invoke(currentIndex)
            return
        }
        val direction = if (bounded > currentIndex) FlipDirection.Forward else FlipDirection.Backward
        // Hop neighbours to sit adjacent to the target, then animate one step.
        currentIndex = if (direction == FlipDirection.Forward) bounded - 1 else bounded + 1
        refreshPages()
        postOnAnimation { programmaticFlip(direction) }
    }

    fun flipForward(animate: Boolean = true) = programmaticFlip(FlipDirection.Forward, animate)
    fun flipBackward(animate: Boolean = true) = programmaticFlip(FlipDirection.Backward, animate)

    fun currentPageIndex(): Int = currentIndex
    fun totalPageCount(): Int = pages.size
    fun currentPage(): Page? = pages.getOrNull(currentIndex)

    // ---- Internal ---------------------------------------------------------

    private fun refreshPages() {
        val style = this.style ?: return
        val geom = this.geometry ?: return
        Timber.tag(TAG).d("refreshPages() currentIndex=$currentIndex / ${pages.size} (prev=${pages.getOrNull(currentIndex - 1) != null} next=${pages.getOrNull(currentIndex + 1) != null})")
        prevView.bind(pages.getOrNull(currentIndex - 1), style, geom, bitmapSource, PageOverlays.EMPTY, backgroundBitmap)
        currentView.bind(pages.getOrNull(currentIndex), style, geom, bitmapSource, overlays, backgroundBitmap)
        nextView.bind(pages.getOrNull(currentIndex + 1), style, geom, bitmapSource, PageOverlays.EMPTY, backgroundBitmap)
        animator.onReset(prevView, currentView, nextView)
        currentView.bringToFront()
    }

    private fun incomingView(direction: FlipDirection): PageView =
        if (direction == FlipDirection.Forward) nextView else prevView

    private fun programmaticFlip(direction: FlipDirection, animate: Boolean = true) {
        Timber.tag(TAG).d("programmaticFlip direction=$direction animate=$animate currentIndex=$currentIndex canFlip=${canFlip(direction)}")
        if (!canFlip(direction)) {
            onEdgeHit?.invoke(direction)
            return
        }
        if (!animate) {
            currentIndex += direction.sign.toInt()
            refreshPages()
            onPageChanged?.invoke(currentIndex)
            return
        }
        dragDirection = direction
        dragProgress = 0f
        val incoming = incomingView(direction)
        animator.onDragStart(currentView, incoming, direction)
        animateSettle(from = 0f, to = 1f, commit = true)
    }

    private fun canFlip(direction: FlipDirection): Boolean = when (direction) {
        FlipDirection.Forward -> currentIndex < pages.size - 1
        FlipDirection.Backward -> currentIndex > 0
    }

    private fun commitFlip(direction: FlipDirection) {
        val oldIndex = currentIndex
        currentIndex = (currentIndex + direction.sign.toInt()).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        Timber.tag(TAG).d("commitFlip direction=$direction  $oldIndex -> $currentIndex")
        animator.onReset(prevView, currentView, nextView)
        refreshPages()
        onPageChanged?.invoke(currentIndex)
    }

    private fun cancelFlip() {
        Timber.tag(TAG).d("cancelFlip dir=$dragDirection progress=$dragProgress")
        animator.onReset(prevView, currentView, nextView)
    }

    // ---- Touch pipeline ---------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (touchLocked) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Timber.tag(TAG).v("intercept DOWN at (${ev.x}, ${ev.y})")
                dragStartX = ev.x
                dragStartY = ev.y
                dragDownTime = System.currentTimeMillis()
                isDragging = false
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.x - dragStartX
                val dy = ev.y - dragStartY
                if (!isDragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    val dir = if (dx < 0) FlipDirection.Forward else FlipDirection.Backward
                    Timber.tag(TAG).d("intercept drag start dx=$dx dy=$dy dir=$dir canFlip=${canFlip(dir)}")
                    isDragging = true
                    dragDirection = dir
                    if (!canFlip(dragDirection)) {
                        isDragging = false
                        return false
                    }
                    val incoming = incomingView(dragDirection)
                    animator.onDragStart(currentView, incoming, dragDirection)
                    if (animator is CoverFlipAnimator) {
                        incoming.bringToFront()
                    } else {
                        currentView.bringToFront()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                releaseVelocityTracker()
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchLocked) return false
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Timber.tag(TAG).v("touch DOWN at (${event.x}, ${event.y})")
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain()
                }
                velocityTracker?.addMovement(event)
                dragStartX = event.x
                dragStartY = event.y
                dragDownTime = System.currentTimeMillis()
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - dragStartX
                    val progress = ((-dx) / width.toFloat() * dragDirection.sign).coerceIn(0f, 1f)
                    dragProgress = progress
                    animator.onDragProgress(progress, dragDirection, currentView, incomingView(dragDirection), width)
                    if (animator.overridesCanvas) {
                        invalidate()
                    }
                    return true
                } else {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        val dir = if (dx < 0) FlipDirection.Forward else FlipDirection.Backward
                        Timber.tag(TAG).d("touch drag start dx=$dx dy=$dy dir=$dir canFlip=${canFlip(dir)}")
                        isDragging = true
                        dragDirection = dir
                        if (canFlip(dragDirection)) {
                            val incoming = incomingView(dragDirection)
                            animator.onDragStart(currentView, incoming, dragDirection)
                        } else {
                            isDragging = false
                            return true
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - dragDownTime
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                val travel = kotlin.math.hypot(dx, dy)
                Timber.tag(TAG).d("touch UP isDragging=$isDragging elapsed=$elapsed dx=$dx travel=$travel progress=$dragProgress")
                if (!isDragging && elapsed < tapMaxDurationMs && travel < tapMaxDistancePx) {
                    handleTap(event.x, event.y)
                } else if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    endDrag(dx, vx)
                } else {
                    Timber.tag(TAG).w("touch UP ignored: not a tap and not dragging (swiped < slop)")
                }
                releaseVelocityTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) endDrag(0f, 0f)
                releaseVelocityTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastTapUpTime) <= doubleTapMaxMs &&
            kotlin.math.hypot(x - lastTapX, y - lastTapY) < tapMaxDistancePx * 2
        lastTapUpTime = now
        lastTapX = x
        lastTapY = y
        if (isDoubleTap) {
            onDoubleTapAt?.invoke(x, y)
            return
        }
        // Single-tap zones: thirds horizontally.
        val third = width / 3f
        when {
            x < third -> flipBackward()
            x > width - third -> flipForward()
            else -> onTapCenter?.invoke()
        }
    }

    private fun endDrag(dx: Float, velocityX: Float) {
        if (!isDragging) return
        isDragging = false
        val commitByProgress = dragProgress >= animator.commitThreshold
        val signedVel = velocityX * -dragDirection.sign
        val commitByVelocity = signedVel >= animator.commitVelocityPxPerSec
        val cancelByVelocity = velocityX * dragDirection.sign >= animator.commitVelocityPxPerSec * 0.6f
        val commit = (commitByProgress || commitByVelocity) && !cancelByVelocity
        Timber.tag(TAG).d("endDrag dx=$dx vx=$velocityX progress=$dragProgress commitByProgress=$commitByProgress commitByVelocity=$commitByVelocity cancelByVelocity=$cancelByVelocity commit=$commit")
        if (commit && !canFlip(dragDirection)) {
            onEdgeHit?.invoke(dragDirection)
            animateSettle(from = dragProgress, to = 0f, commit = false)
            return
        }
        animateSettle(from = dragProgress, to = if (commit) 1f else 0f, commit = commit)
    }

    private fun animateSettle(from: Float, to: Float, commit: Boolean) {
        cancelSettle()
        val duration = (animator.durationMs * abs(to - from)).toLong().coerceAtLeast(0L)
        Timber.tag(TAG).d("animateSettle from=$from to=$to commit=$commit duration=${duration}ms animator=${animator::class.simpleName}")
        if (duration == 0L) {
            dragProgress = to
            if (commit) commitFlip(dragDirection) else cancelFlip()
            invalidate()
            return
        }
        val incoming = incomingView(dragDirection)
        settleAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val p = va.animatedValue as Float
                dragProgress = p
                animator.onDragProgress(p, dragDirection, currentView, incoming, width)
                if (animator.overridesCanvas) invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (commit) commitFlip(dragDirection) else cancelFlip()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelSettle() {
        settleAnimator?.cancel()
        settleAnimator = null
    }

    private fun cancelAllGestures() {
        cancelSettle()
        isDragging = false
        dragProgress = 0f
        releaseVelocityTracker()
        animator.onReset(prevView, currentView, nextView)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- Canvas-override path for Simulation ------------------------------

    override fun dispatchDraw(canvas: Canvas) {
        val style = this.style
        if (style != null) {
            PageRenderer.drawBackground(canvas, width, height, style, backgroundBitmap)
        }
        if (animator.overridesCanvas && (isDragging || settleAnimator?.isRunning == true)) {
            // Host-driven custom rendering: don't let children draw.
            val curBmp = currentView.captureSnapshot()
            val incBmp = incomingView(dragDirection).captureSnapshot()
            animator.drawOverlay(canvas, dragProgress, dragDirection, curBmp, incBmp, width, height)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllGestures()
    }

    private companion object {
        const val TAG = "NovelReaderView"
    }
}
