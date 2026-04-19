package ceui.pixiv.ui.novel.reader.render.flip

import android.graphics.Canvas
import ceui.pixiv.ui.novel.reader.render.PageView

/**
 * Abstract flip transition. The [NovelReaderView] hands a progress value in
 * [0f, 1f] plus a [FlipDirection]; animators decide how to paint the current
 * and incoming pages (or manipulate [PageView] transforms directly).
 *
 * Lifecycle (driven by NovelReaderView):
 *  1. `onDragStart` — touch down, snapshot any bitmaps needed.
 *  2. repeated `onDragProgress` as finger moves.
 *  3. `onSettle` — finger released. Animator returns whether to commit the flip.
 *  4. `onReset` — committed (or cancelled) animation completed, pages reshuffled.
 *
 * Most modes manipulate the PageView's translationX/alpha/rotationY. The
 * canvas-override modes (e.g. Simulation) set [overridesCanvas] to `true` and
 * paint via [drawOverlay].
 */
abstract class FlipAnimator {

    open val durationMs: Long = 320L

    /** If `true`, the host will call [drawOverlay] every frame and keep the
     *  child PageViews hidden so drawing happens on a shared canvas. */
    open val overridesCanvas: Boolean = false

    /** Progress threshold that decides commit vs. cancel when the user lifts
     *  their finger with near-zero velocity. */
    open val commitThreshold: Float = 0.35f

    /** Velocity threshold (px/sec, absolute value) that forces a commit regardless
     *  of progress. */
    open val commitVelocityPxPerSec: Float = 1400f

    open fun onDragStart(current: PageView, incoming: PageView, direction: FlipDirection) {
        current.translationX = 0f
        incoming.translationX = incoming.width.toFloat() * direction.sign
        current.alpha = 1f
        incoming.alpha = 1f
        current.visibility = android.view.View.VISIBLE
        incoming.visibility = android.view.View.VISIBLE
    }

    /** Paint or re-position per-view transforms for the given progress. */
    abstract fun onDragProgress(
        progress: Float,
        direction: FlipDirection,
        current: PageView,
        incoming: PageView,
        width: Int,
    )

    /** Called only when [overridesCanvas] is true. */
    open fun drawOverlay(
        canvas: Canvas,
        progress: Float,
        direction: FlipDirection,
        currentBitmap: android.graphics.Bitmap?,
        incomingBitmap: android.graphics.Bitmap?,
        width: Int,
        height: Int,
    ) = Unit

    open fun onReset(prev: PageView, current: PageView, next: PageView) {
        for (view in arrayOf(prev, current, next)) {
            view.translationX = 0f
            view.translationY = 0f
            view.rotationY = 0f
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.visibility = android.view.View.VISIBLE
        }
    }
}

enum class FlipDirection(val sign: Float) {
    Forward(1f),
    Backward(-1f),
    ;

    val opposite: FlipDirection get() = if (this == Forward) Backward else Forward
}

/** Final outcome of a finger release. */
enum class FlipOutcome { Commit, Cancel }
