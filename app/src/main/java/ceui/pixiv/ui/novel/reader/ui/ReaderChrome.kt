package ceui.pixiv.ui.novel.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Coordinates show/hide of the top and bottom bars together. Both slide in from
 * their respective edges and fade in alpha; tapping the centre of the reader
 * toggles visibility. The current state is exposed so the Fragment can decide
 * how to route tap-zones when bars are up vs. down.
 */
class ReaderChrome(
    private val topBar: ReaderTopBar,
    private val bottomBar: ReaderBottomBar,
) {
    private val topView: View = topBar.view
    private val bottomView: View = bottomBar.view

    private var shown: Boolean = false
    private val animDuration = 220L
    private val interpolator = AccelerateDecelerateInterpolator()

    val isShown: Boolean get() = shown

    init {
        // Start hidden.
        topView.alpha = 0f
        bottomView.alpha = 0f
        topView.visibility = View.GONE
        bottomView.visibility = View.GONE
    }

    fun show(animate: Boolean = true) {
        if (shown) return
        shown = true
        animateTo(target = 1f, animate, onEnd = null)
        topView.visibility = View.VISIBLE
        bottomView.visibility = View.VISIBLE
    }

    fun hide(animate: Boolean = true) {
        if (!shown) return
        shown = false
        animateTo(target = 0f, animate, onEnd = {
            topView.visibility = View.GONE
            bottomView.visibility = View.GONE
        })
    }

    fun toggle() {
        if (shown) hide() else show()
    }

    private fun animateTo(target: Float, animate: Boolean, onEnd: (() -> Unit)?) {
        if (!animate) {
            topView.alpha = target
            bottomView.alpha = target
            topView.translationY = if (target == 1f) 0f else -topView.height.toFloat()
            bottomView.translationY = if (target == 1f) 0f else bottomView.height.toFloat()
            onEnd?.invoke()
            return
        }
        topView.animate().cancel()
        bottomView.animate().cancel()

        val topStartY = if (target == 1f) -topView.height.toFloat() else 0f
        val topEndY = if (target == 1f) 0f else -topView.height.toFloat()
        val bottomStartY = if (target == 1f) bottomView.height.toFloat() else 0f
        val bottomEndY = if (target == 1f) 0f else bottomView.height.toFloat()

        topView.translationY = topStartY
        bottomView.translationY = bottomStartY
        topView.animate()
            .alpha(target)
            .translationY(topEndY)
            .setDuration(animDuration)
            .setInterpolator(interpolator)
            .start()
        bottomView.animate()
            .alpha(target)
            .translationY(bottomEndY)
            .setDuration(animDuration)
            .setInterpolator(interpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }
}
