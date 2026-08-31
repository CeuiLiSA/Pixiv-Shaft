package ceui.pixiv.banner

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Per-Activity glue between [BannerManager] and a [BannerHost]. Subscribes
 * to [BannerManager.state] while the lifecycle is at least STARTED, and
 * mirrors the controller's current state into the host: animates the
 * presented banner in, swaps it out when the controller advances, and
 * removes it on Idle / Shutdown.
 */
internal class BannerPresenter(
    private val host: BannerHost,
    private val manager: BannerManager,
    private val lifecycle: Lifecycle,
) {

    private var currentView: View? = null
    private var currentRenderedId: String? = null
    private var collectJob: Job? = null

    fun attach() {
        collectJob = lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.state.collect { state -> renderState(state) }
            }
        }
    }

    fun detach() {
        collectJob?.cancel()
        collectJob = null
        host.removeAllViews()
        currentView = null
        currentRenderedId = null
        (host.parent as? ViewGroup)?.removeView(host)
    }

    private fun renderState(state: BannerState) {
        when (state) {
            BannerState.Idle, BannerState.Shutdown -> animateOutCurrent()
            is BannerState.Presenting -> {
                if (state.request.id == currentRenderedId) return
                currentView?.animate()?.cancel()
                host.removeAllViews()
                currentView = null
                currentRenderedId = null
                animateIn(state.request)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun animateIn(request: BannerRequest) {
        val binderKey = when (request) {
            is BannerRequest.Custom -> request.binderKey
            is BannerRequest.Text -> BannerViewBinder.DEFAULT_KEY
        }
        val binder = manager.binderFor(binderKey)
            ?: manager.binderFor(BannerViewBinder.DEFAULT_KEY)
            ?: error(
                "No BannerViewBinder registered for key=\"$binderKey\" and no \"${BannerViewBinder.DEFAULT_KEY}\" " +
                    "fallback. Register at least one binder under \"${BannerViewBinder.DEFAULT_KEY}\" when " +
                    "constructing the BannerManager.",
            )

        val view = binder.create(host)
        binder.bind(view, request, callbacksFor(request))
        host.addView(view)

        view.alpha = 0f
        view.post {
            if (view.parent !== host) return@post
            val offset = -view.height.toFloat().coerceAtLeast(1f)
            view.translationY = offset
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIM_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        attachSwipeToDismiss(view, request.id)

        currentView = view
        currentRenderedId = request.id
    }

    private fun animateOutCurrent() {
        val view = currentView ?: run {
            host.removeAllViews()
            return
        }
        currentView = null
        currentRenderedId = null
        view.animate().cancel()
        view.animate()
            .translationY(-view.height.toFloat().coerceAtLeast(1f))
            .alpha(0f)
            .setDuration(ANIM_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (view.parent === host) host.removeView(view)
            }
            .start()
    }

    private fun callbacksFor(request: BannerRequest): BannerCallbacks =
        object : BannerCallbacks {
            override fun dismiss(reason: BannerDismissReason) {
                manager.dismiss(request.id, reason)
            }

            override fun triggerTap() {
                manager.notifyTapped(request.id)
            }

            override fun triggerAction(actionKey: String?) {
                manager.notifyActionTapped(request.id, actionKey)
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToDismiss(view: View, id: String) {
        val gesture = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (velocityY < -SWIPE_VELOCITY_THRESHOLD) {
                    manager.dismiss(id, BannerDismissReason.UserSwipe)
                    return true
                }
                return false
            }
        })
        view.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev) }
    }

    companion object {
        private const val ANIM_IN_MS: Long = 250
        private const val ANIM_OUT_MS: Long = 200
        private const val SWIPE_VELOCITY_THRESHOLD: Float = 800f
    }
}
