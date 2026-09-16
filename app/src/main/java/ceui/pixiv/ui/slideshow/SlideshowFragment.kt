package ceui.pixiv.ui.slideshow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.pixiv.imageloader.Disposable
import ceui.pixiv.imageloader.ImageLoadState
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.pixiv.imageloader.observeState
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import timber.log.Timber
import java.io.File
import kotlin.random.Random

class SlideshowFragment : Fragment(R.layout.fragment_slideshow) {

    companion object {
        private const val DISPLAY_DURATION_MS = 6000L
        private const val CROSSFADE_DURATION_MS = 700L
        private const val PRELOAD_AHEAD = 2
        private const val MAX_ZOOM = 1.18f
        private const val MIN_ZOOM = 1.08f
    }

    private lateinit var rootView: FrameLayout
    private lateinit var imageA: ImageView
    private lateinit var imageB: ImageView
    private lateinit var loadingOverlay: View
    private lateinit var controlsView: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var indicatorText: TextView
    private lateinit var pauseButton: ImageView

    private var frontIsA = true

    private var session: SlideshowStore.Session? = null
    private var sessionId: String? = null

    private var sequence: IntArray = intArrayOf()
    private var positionInSequence: Int = 0

    private var isPaused = false

    /** Bumped on every navigation action. Pending image-load callbacks compare against this
     * before doing anything, so a still-downloading prev/next no longer fires after the user
     * skipped past it. */
    private var loadEpoch: Long = 0
    private var pendingLoad: Disposable? = null

    /** Owned handler so cleanup is deterministic. `view?.postDelayed/removeCallbacks` is fragile:
     * once the view detaches, View.removeCallbacks silently skips the looper queue and only clears
     * the RunQueue — so any runnable posted while attached can survive a "cleanup" call and fire
     * after the fragment is gone. With an owned Handler, removeCallbacks always works. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val advanceRunnable = Runnable { advanceToNext() }
    private var frontKenBurns: AnimatorSet? = null
    private var backKenBurns: AnimatorSet? = null
    private var crossfadeAnim: AnimatorSet? = null
    private var hideControlsRunnable: Runnable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootView = view as FrameLayout
        imageA = view.findViewById(R.id.slide_image_a)
        imageB = view.findViewById(R.id.slide_image_b)
        loadingOverlay = view.findViewById(R.id.slide_loading_overlay)
        controlsView = view.findViewById(R.id.slide_controls)
        topBar = view.findViewById(R.id.slide_top_bar)
        bottomBar = view.findViewById(R.id.slide_bottom_bar)
        indicatorText = view.findViewById(R.id.slide_indicator)
        pauseButton = view.findViewById(R.id.slide_pause)

        applySystemBarInsets()

        sessionId = arguments?.getString(SlideshowActivity.EXTRA_SESSION_ID)
            ?: requireActivity().intent?.getStringExtra(SlideshowActivity.EXTRA_SESSION_ID)
        val s = sessionId?.let { SlideshowStore.get(it) }
        if (s == null || s.urls.isEmpty()) {
            Timber.w("[Slideshow] missing session, finishing")
            requireActivity().finish()
            return
        }
        session = s

        sequence = buildSequence(s.urls.size, s.startIndex, s.random)
        positionInSequence = 0
        updateIndicator()

        view.findViewById<ImageView>(R.id.slide_back).setOnClickListener {
            requireActivity().finish()
        }
        pauseButton.setOnClickListener { togglePause() }
        view.findViewById<ImageView>(R.id.slide_prev).setOnClickListener { jumpRelative(-1) }
        view.findViewById<ImageView>(R.id.slide_next).setOnClickListener { jumpRelative(+1) }

        rootView.setOnClickListener { toggleControls() }

        scheduleControlsHide()
        showCurrentImage(initial = true)
    }

    private fun applySystemBarInsets() {
        val root = view ?: return
        // 首页在旧系统启用兼容分发后，FragmentContainerView 只保留 ViewCompat 注册的监听器。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = sb.top + 12.ppppx)
            bottomBar.updatePadding(bottom = sb.bottom + 20.ppppx)
            insets
        }
    }

    private fun buildSequence(size: Int, startIndex: Int, random: Boolean): IntArray {
        if (size <= 0) return intArrayOf()
        val safeStart = startIndex.coerceIn(0, size - 1)
        return if (!random) {
            IntArray(size) { (safeStart + it) % size }
        } else {
            val rest = (0 until size).filter { it != safeStart }.toMutableList()
            rest.shuffle(Random.Default)
            (intArrayOf(safeStart) + rest.toIntArray())
        }
    }

    private fun jumpRelative(delta: Int) {
        val s = session ?: return
        if (sequence.isEmpty()) return
        val newPos = (positionInSequence + delta).coerceIn(0, sequence.size - 1)
        if (newPos == positionInSequence) return
        positionInSequence = newPos
        mainHandler.removeCallbacks(advanceRunnable)
        cancelTransition()
        showCurrentImage(initial = false)
        // Suppress the auto-advance schedule from showCurrentImage's success path? It calls
        // queueAdvance after a successful display, so we don't need to do anything else.
        updateIndicator()
        preloadAhead()
        scheduleControlsHide()
    }

    /**
     * Loads the image at the current position and shows it. For the very first image
     * (initial=true) we cross-fade from black; for subsequent shows we cross-fade from the
     * previous image. We never advance to a position whose file isn't on disk yet — instead we
     * keep the previous image's Ken Burns running and observe until the download completes.
     */
    private fun showCurrentImage(initial: Boolean) {
        val s = session ?: return
        val idx = sequence.getOrNull(positionInSequence) ?: return
        val url = s.urls.getOrNull(idx) ?: return

        loadEpoch += 1
        val myEpoch = loadEpoch

        // Always make sure download is queued.
        ensurePreload(url)
        val task = ImageLoaderV3.obtain(url, s.titles.getOrNull(idx).orEmpty())
        // 上一次失败的任务在(重新)展示时重来一次，对齐旧 TaskPool「取到 errored 即重下」，避免卡在黑屏。
        if (task.state.value is ImageLoadState.Error) task.retry()
        val cached = task.currentFile
        if (cached != null && cached.exists()) {
            if (initial) displayFirstImage(cached) else performTransition(cached)
            return
        }
        // Need to wait. Show the loading dim only on initial / manual jumps so auto-advance
        // stays seamless (the previous image keeps playing).
        loadingOverlay.isVisible = true
        // 摘掉上一张还在等的观察，避免长时间放映累积 collector。
        pendingLoad?.dispose()
        pendingLoad = task.observeState(viewLifecycleOwner) { state ->
            if (myEpoch != loadEpoch) return@observeState
            val file = (state as? ImageLoadState.Success)?.file ?: return@observeState
            if (!file.exists()) return@observeState
            if (!isAdded || view == null) return@observeState
            // One-shot: bump the epoch so any duplicate emit (shouldn't happen but be safe) is
            // ignored.
            loadEpoch += 1
            if (initial) displayFirstImage(file) else performTransition(file)
        }
    }

    private fun displayFirstImage(file: File) {
        crossfadeAnim?.cancel(); crossfadeAnim = null
        frontKenBurns?.cancel(); frontKenBurns = null
        backKenBurns?.cancel(); backKenBurns = null
        imageA.alpha = 0f
        imageB.alpha = 0f
        resetTransform(imageA)
        resetTransform(imageB)
        frontIsA = true

        val target = imageA
        val totalKenBurnsMs = DISPLAY_DURATION_MS + CROSSFADE_DURATION_MS
        val expectedEpoch = loadEpoch
        loadIntoImageView(target, file, expectedEpoch, onFail = { /* genuine fail: stay on black, observer-side will retry */ }) {
            if (!isAdded || view == null) return@loadIntoImageView
            loadingOverlay.isVisible = false
            target.alpha = 0f
            target.animate().alpha(1f).setDuration(CROSSFADE_DURATION_MS).start()
            frontKenBurns = startKenBurns(target, totalKenBurnsMs)
            queueAdvance()
            preloadAhead()
            updateIndicator()
        }
    }

    private fun queueAdvance() {
        mainHandler.removeCallbacks(advanceRunnable)
        if (!isPaused) {
            mainHandler.postDelayed(advanceRunnable, DISPLAY_DURATION_MS)
        }
    }

    private fun advanceToNext() {
        if (!isAdded || view == null || activity == null) return
        val s = session ?: return
        if (sequence.isEmpty() || isPaused) return
        val nextPos = positionInSequence + 1
        if (nextPos >= sequence.size) {
            if (s.random) {
                sequence = buildSequence(s.urls.size, sequence[0], true)
            }
            positionInSequence = 0
        } else {
            positionInSequence = nextPos
        }
        showCurrentImage(initial = false)
    }

    private fun cancelTransition() {
        crossfadeAnim?.cancel(); crossfadeAnim = null
        backKenBurns?.cancel(); backKenBurns = null
    }

    /**
     * Cross-fade from the current front view to the new file. Both images' Ken Burns animations
     * run during the overlap. After the fade completes the old front becomes the back.
     */
    private fun performTransition(file: File) {
        loadingOverlay.isVisible = false
        val newFront = if (frontIsA) imageB else imageA
        val oldFront = if (frontIsA) imageA else imageB
        val expectedEpoch = loadEpoch

        loadIntoImageView(
            newFront, file, expectedEpoch,
            // Genuine load failure on the current image: skip to the next one rather than
            // crossfading to nothing. The previous image keeps playing in the meantime.
            onFail = { if (expectedEpoch == loadEpoch && !isPaused) advanceToNext() }
        ) {
            if (!isAdded || view == null) return@loadIntoImageView
            crossfadeAnim?.cancel()
            backKenBurns?.cancel()

            resetTransform(newFront)
            newFront.alpha = 0f
            val totalMs = DISPLAY_DURATION_MS + CROSSFADE_DURATION_MS
            val newKB = startKenBurns(newFront, totalMs)

            val fadeIn = ObjectAnimator.ofFloat(newFront, View.ALPHA, 0f, 1f)
            val fadeOut = ObjectAnimator.ofFloat(oldFront, View.ALPHA, oldFront.alpha, 0f)
            val set = AnimatorSet().apply {
                duration = CROSSFADE_DURATION_MS
                interpolator = LinearInterpolator()
                playTogether(fadeIn, fadeOut)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isAdded || this@SlideshowFragment.view == null) return
                        frontKenBurns?.cancel()
                        oldFront.alpha = 0f
                        frontIsA = !frontIsA
                        frontKenBurns = newKB
                        backKenBurns = null
                        queueAdvance()
                        preloadAhead()
                        updateIndicator()
                    }
                })
            }
            crossfadeAnim = set
            set.start()
        }
    }

    private fun preloadAhead() {
        val s = session ?: return
        for (i in 1..PRELOAD_AHEAD) {
            val pos = positionInSequence + i
            if (pos >= sequence.size) break
            val idx = sequence.getOrNull(pos) ?: break
            val url = s.urls.getOrNull(idx) ?: break
            ensurePreload(url)
        }
    }

    private fun ensurePreload(url: String) {
        ImageLoaderV3.obtain(url)
    }

    /**
     * Loads `file` into `target` via Glide. Calls onReady() once Glide has committed the
     * drawable; calls onFail() on a genuine load failure. Both are gated by `expectedEpoch`:
     * if the user has navigated since this load was kicked off, the captured epoch no longer
     * matches `loadEpoch` and both callbacks become no-ops — preventing a stale or cancelled
     * load from triggering a crossfade to wrong / empty content (the user's hard requirement
     * is no black frame between images).
     */
    private fun loadIntoImageView(
        target: ImageView,
        file: File,
        expectedEpoch: Long,
        onFail: () -> Unit = {},
        onReady: () -> Unit,
    ) {
        if (!isAdded || activity == null || view == null) return
        Glide.with(this)
            .load(file)
            .dontAnimate()
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    t: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Timber.w(e, "[Slideshow] Glide failed for ${file.path}")
                    target.post {
                        if (expectedEpoch != loadEpoch || !isAdded) return@post
                        onFail()
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    t: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    target.post {
                        if (expectedEpoch != loadEpoch || !isAdded) return@post
                        onReady()
                    }
                    return false
                }
            })
            .into(target)
    }

    private fun startKenBurns(target: ImageView, durationMs: Long): AnimatorSet {
        val width = target.width.takeIf { it > 0 } ?: rootView.width
        val height = target.height.takeIf { it > 0 } ?: rootView.height
        val endScale = MIN_ZOOM + Random.nextFloat() * (MAX_ZOOM - MIN_ZOOM)
        val slackX = width * (endScale - 1f) / 2f
        val slackY = height * (endScale - 1f) / 2f
        val tx = (Random.nextFloat() * 2f - 1f) * slackX
        val ty = (Random.nextFloat() * 2f - 1f) * slackY

        val sx = ObjectAnimator.ofFloat(target, View.SCALE_X, 1f, endScale)
        val sy = ObjectAnimator.ofFloat(target, View.SCALE_Y, 1f, endScale)
        val txa = ObjectAnimator.ofFloat(target, View.TRANSLATION_X, 0f, tx)
        val tya = ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, 0f, ty)
        val set = AnimatorSet().apply {
            playTogether(sx, sy, txa, tya)
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
        }
        set.start()
        return set
    }

    private fun resetTransform(target: ImageView) {
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.translationY = 0f
    }

    private fun togglePause() {
        isPaused = !isPaused
        pauseButton.setImageResource(
            if (isPaused) R.drawable.ic_baseline_play_arrow_24
            else R.drawable.ic_baseline_pause_24
        )
        if (isPaused) {
            mainHandler.removeCallbacks(advanceRunnable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                frontKenBurns?.pause()
                backKenBurns?.pause()
            }
        } else {
            queueAdvance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                frontKenBurns?.resume()
                backKenBurns?.resume()
            }
        }
        scheduleControlsHide()
    }

    private fun toggleControls() {
        if (controlsView.alpha < 0.5f || !controlsView.isVisible) {
            controlsView.alpha = 0f
            controlsView.isVisible = true
            controlsView.animate().alpha(1f).setDuration(180).start()
            scheduleControlsHide()
        } else {
            hideControls()
        }
    }

    private fun scheduleControlsHide() {
        hideControlsRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { hideControls() }
        hideControlsRunnable = r
        mainHandler.postDelayed(r, 3000)
    }

    private fun hideControls() {
        controlsView.animate().alpha(0f).setDuration(220)
            .withEndAction { if (isAdded) controlsView.isVisible = false }.start()
    }

    private fun updateIndicator() {
        val s = session ?: return
        val idx = sequence.getOrNull(positionInSequence) ?: return
        val title = s.titles.getOrNull(idx)?.takeIf { it.isNotBlank() }
        indicatorText.text = if (title != null) {
            "${positionInSequence + 1} / ${sequence.size}  ·  $title"
        } else {
            "${positionInSequence + 1} / ${sequence.size}"
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(advanceRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            frontKenBurns?.pause()
            backKenBurns?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isPaused && view != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                frontKenBurns?.resume()
                backKenBurns?.resume()
            }
            queueAdvance()
        }
    }

    override fun onDestroyView() {
        crossfadeAnim?.cancel(); crossfadeAnim = null
        frontKenBurns?.cancel(); frontKenBurns = null
        backKenBurns?.cancel(); backKenBurns = null
        mainHandler.removeCallbacksAndMessages(null)
        hideControlsRunnable = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // Configuration changes recreate the Fragment and still need the
        // process-local handoff. Release only when this slideshow is actually
        // leaving the task.
        if (activity?.isFinishing == true && activity?.isChangingConfigurations != true) {
            sessionId?.let { SlideshowStore.remove(it) }
        }
        super.onDestroy()
    }
}
