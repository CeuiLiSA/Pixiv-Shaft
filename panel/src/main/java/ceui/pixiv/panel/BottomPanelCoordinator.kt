package ceui.pixiv.panel

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView

/**
 * Orchestrates smooth transitions between soft keyboard and a custom
 * bottom panel (emoji, sticker, voice, etc.).
 *
 * The coordinator owns the three-state machine ([PanelState]) and the
 * animations. It knows nothing about what the panel contains — only
 * the [PanelHost] contract and the panel [View].
 *
 * ## Usage
 *
 * ```kotlin
 * class MyChatFragment : Fragment(R.layout.fragment_chat) {
 *     private val binding by viewBinding(FragmentChatBinding::bind)
 *     private lateinit var panelCoordinator: BottomPanelCoordinator
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         super.onViewCreated(view, savedInstanceState)
 *         panelCoordinator = BottomPanelCoordinator(
 *             host = object : PanelHost {
 *                 override val panelRoot get() = binding.root
 *                 override val panelView get() = binding.emojiPanel
 *                 override val panelInputView get() = binding.etInput
 *                 override fun onAnchorContent() { binding.recyclerView.scrollToPosition(0) }
 *                 override fun onPanelStateChanged(state: PanelState) { updateIcon(state) }
 *             },
 *         )
 *         panelCoordinator.attach(this)
 *
 *         binding.btnEmoji.setOnClickListener { panelCoordinator.toggle() }
 *     }
 * }
 * ```
 */
class BottomPanelCoordinator(
    private val host: PanelHost,
    private val fallbackHeightDp: Int = 270,
    private val animDurationMs: Long = 250,
) {

    /** Current state (read-only for consumers). */
    var state: PanelState = PanelState.NONE
        private set

    private var savedKeyboardHeight: Int = 0
    private var navBarHeight: Int = 0
    private var panelAnimator: ValueAnimator? = null
    private var backCallback: OnBackPressedCallback? = null
    private var dismissStartedDispatched = false
    private var dismissTouchView: RecyclerView? = null
    private var dismissTouchListener: RecyclerView.OnItemTouchListener? = null
    private var hostWindow: Window? = null
    private val panelBasePaddingBottom = host.panelView.paddingBottom

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Attach to a Fragment's view lifecycle. Sets up the
     * [WindowInsetsAnimationCompat] callback and back-press handler.
     * Automatically detaches on view destroy.
     */
    fun attach(fragment: Fragment) {
        hostWindow = (fragment as? DialogFragment)?.dialog?.window
            ?: fragment.requireActivity().window
        setupImeInsets(host.panelRoot)
        setupTapToDismiss()
        setupToggleButton()

        val cb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hidePanel()
            }
        }
        backCallback = cb
        fragment.requireActivity().onBackPressedDispatcher
            .addCallback(fragment.viewLifecycleOwner, cb)

        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                detach()
            }
        })
    }

    private fun detach() {
        cancelPanelAnimation()
        dismissTouchListener?.let { listener ->
            dismissTouchView?.removeOnItemTouchListener(listener)
        }
        dismissTouchListener = null
        dismissTouchView = null
        hostWindow = null
        backCallback?.isEnabled = false
        backCallback = null
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Toggle between PANEL and the current state. */
    fun toggle() {
        when (state) {
            PanelState.NONE -> showPanel()
            PanelState.KEYBOARD -> switchToPanelFromKeyboard()
            PanelState.PANEL -> switchToKeyboard()
        }
    }

    /** Show the panel from NONE, expanding the actual panel from its current frame. */
    fun showPanel() {
        cancelPanelAnimation()
        applyPanelNavigationInset()
        val target = expandedPanelHeight()
        // Transfer the navigation inset from the root to the panel before animating. Both sides of
        // the transfer occupy the same height, so the input row does not jump on the first frame.
        val start = if (host.panelView.isVisible) visiblePanelHeight() else navBarHeight
        host.panelRoot.updatePadding(bottom = 0)
        setPanelHeight(start)
        host.panelView.isVisible = true
        setState(PanelState.PANEL)
        animatePanelHeight(start, target) {
            if (state != PanelState.PANEL) return@animatePanelHeight
            setPanelHeight(target)
            host.panelView.isVisible = true
        }
    }

    /** Hide the visible panel first; publish NONE only after its final animated frame. */
    fun hidePanel() {
        cancelPanelAnimation()
        val start = visiblePanelHeight()
        val end = navBarHeight
        dispatchDismissStarted()
        if (start <= end) {
            finishHidingPanel()
            return
        }
        applyPanelNavigationInset()
        host.panelRoot.updatePadding(bottom = 0)
        host.panelView.isVisible = true
        animatePanelHeight(start, end, ::finishHidingPanel)
    }

    /** Seamless KEYBOARD → PANEL: show panel immediately, dismiss keyboard. */
    fun switchToPanelFromKeyboard() {
        cancelPanelAnimation()
        applyPanelNavigationInset()
        val height = expandedPanelHeight()
        val oldPad = host.panelRoot.paddingBottom
        val newPad = maxOf(oldPad - height, 0)
        setPanelHeight(height)
        host.panelView.isVisible = true
        host.panelRoot.updatePadding(bottom = newPad)
        host.panelView.requestLayout()
        setState(PanelState.PANEL)
        hideKeyboard()
    }

    /** Seamless PANEL → KEYBOARD: keep panel during keyboard animation. */
    fun switchToKeyboard() {
        cancelPanelAnimation()
        setState(PanelState.KEYBOARD)
        showKeyboard()
    }

    /** Dismiss whatever is open (keyboard or panel) → NONE. */
    fun dismiss() {
        when (state) {
            PanelState.KEYBOARD -> {
                cancelPanelAnimation()
                hideKeyboard()
                host.panelInputView?.clearFocus()
            }
            PanelState.PANEL -> hidePanel()
            PanelState.NONE -> {}
        }
    }

    // ── IME insets ───────────────────────────────────────────────────────

    private fun setupImeInsets(root: View) {
        val insetTypes = WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.navigationBars()
        var isImeAnimating = false

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // 只在键盘不可见时采样导航栏高度:部分宿主(如 HyperOS)键盘弹起时会把 navigationBars
            // inset 也报成键盘高度,若照单全收会污染 navBarHeight——键盘→面板切换时
            // switchToPanelFromKeyboard 用 navBarHeight 当面板落位下限,会把面板顶高一整个键盘的
            // 高度、下方露出列表内容。键盘弹起期间保留上一次(键盘收起时)的正确导航栏高度即可。
            if (!imeVisible) {
                navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                applyPanelNavigationInset()
            }
            if (!isImeAnimating) {
                val systemBottom = insets.getInsets(insetTypes).bottom
                val bottom = if (host.panelView.isVisible) {
                    maxOf(systemBottom - occupiedPanelHeight(), 0)
                } else if (state == PanelState.PANEL) {
                    0
                } else {
                    systemBottom
                }
                v.updatePadding(bottom = bottom)

                // Insets animation is optional: hardware keyboards, disabled system animations,
                // and some OEM IMEs can update visibility without invoking onEnd(). Keep the
                // public state canonical from the normal insets path as well. PANEL is excluded
                // because it deliberately remains authoritative while the IME is being dismissed.
                when {
                    imeVisible && host.panelView.isVisible && state == PanelState.KEYBOARD ->
                        finishSwitchToKeyboard(systemBottom)
                    imeVisible && !host.panelView.isVisible && state != PanelState.PANEL ->
                        setState(PanelState.KEYBOARD)
                    !imeVisible && !host.panelView.isVisible && state == PanelState.KEYBOARD ->
                        setState(PanelState.NONE)
                }
            }
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                private var imeBottomBefore = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = true
                        imeBottomBefore = ViewCompat.getRootWindowInsets(root)
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        // Callback ordering guarantees that target insets have been applied before
                        // onStart. Do not infer "closing" in onPrepare: IME height changes (for
                        // example a candidate strip appearing) also animate while remaining visible.
                        val targetInsets = ViewCompat.getRootWindowInsets(root)
                        val targetImeBottom = targetInsets
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        val imeWillRemainVisible =
                            targetInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
                                targetImeBottom > 0
                        if (
                            imeBottomBefore > 0 &&
                            state == PanelState.KEYBOARD &&
                            !imeWillRemainVisible
                        ) {
                            dispatchDismissStarted()
                        }
                        val imeTarget = bounds.upperBound.bottom
                        if (imeTarget > navBarHeight) {
                            savedKeyboardHeight = imeTarget - navBarHeight
                        }
                        if (imeBottomBefore == 0 && !host.panelView.isVisible) {
                            host.onAnchorContent()
                        }
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val imeBottom = insets.getInsets(insetTypes).bottom
                    val bottom = if (host.panelView.isVisible) {
                        maxOf(imeBottom - occupiedPanelHeight(), 0)
                    } else {
                        imeBottom
                    }
                    root.updatePadding(bottom = bottom)
                    host.onAnchorContent()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = false
                        val rootInsets = ViewCompat.getRootWindowInsets(root)
                        val imeNow = rootInsets
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        val endBottom = rootInsets?.getInsets(insetTypes)?.bottom ?: imeNow
                        // 键盘可见性:优先信任 isVisible(ime) 这个 canonical 标志位——`imeNow >
                        // navBarHeight` 只是尺寸启发式,某些宿主(如 HyperOS)键盘弹起时把 navigationBars
                        // inset 也报成键盘高度,尺寸比较会把开着的键盘误判成收起。OR 上 isVisible 只会让
                        //「键盘确实开着」更可靠地判成 true,不影响收起方向。
                        val imeVisibleFlag =
                            rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        val keyboardVisible = imeNow > navBarHeight || imeVisibleFlag

                        // A system/back gesture may reverse after onStart. Reconcile the transient
                        // dismissal phase before publishing the final stable state so the host can
                        // restore its input row and scrim without receiving a fake state change.
                        if (
                            keyboardVisible &&
                            state == PanelState.KEYBOARD &&
                            dismissStartedDispatched
                        ) {
                            cancelDismissStarted()
                        }

                        if (keyboardVisible && host.panelView.isVisible) {
                            finishSwitchToKeyboard(endBottom)
                        } else if (keyboardVisible) {
                            setState(PanelState.KEYBOARD)
                        } else if (!host.panelView.isVisible) {
                            setState(PanelState.NONE)
                        }

                        ViewCompat.requestApplyInsets(root)
                    }
                }
            },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setupToggleButton() {
        val btn = host.panelToggleButton ?: return
        btn.setOnClickListener { toggle() }
        // Tap input while panel is open → switch to keyboard
        host.panelInputView?.setOnClickListener {
            if (state == PanelState.PANEL) switchToKeyboard()
        }
    }

    private fun setupTapToDismiss() {
        val contentView = host.panelContentView ?: return
        val touchSlop = ViewConfiguration.get(contentView.context).scaledTouchSlop.toFloat()
        val touchSlopSquared = touchSlop * touchSlop
        val listener = object : RecyclerView.SimpleOnItemTouchListener() {
            private var trackingTap = false
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        trackingTap = state != PanelState.NONE && event.pointerCount == 1
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        trackingTap = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (dx * dx + dy * dy > touchSlopSquared) trackingTap = false
                    }
                    MotionEvent.ACTION_UP -> {
                        // The gesture's ownership is decided on DOWN. Even if an OEM closes the
                        // IME before UP arrives, this first tap must not fall through and activate
                        // an artwork item that was covered when the gesture began.
                        val shouldConsume = trackingTap
                        trackingTap = false
                        if (shouldConsume) {
                            if (state != PanelState.NONE) dismiss()
                            // Consume the first tap so the underlying artwork item is not also
                            // clicked. Scroll gestures were already released after touchSlop.
                            return true
                        }
                    }
                }
                return false
            }
        }
        dismissTouchView = contentView
        dismissTouchListener = listener
        contentView.addOnItemTouchListener(listener)
    }

    private fun setState(new: PanelState) {
        if (state == new) return
        state = new
        if (new != PanelState.NONE) dismissStartedDispatched = false
        backCallback?.isEnabled = new == PanelState.PANEL
        host.panelToggleButton?.setImageResource(
            if (new == PanelState.PANEL) host.keyboardToggleIconRes
            else host.panelToggleIconRes
        )
        host.onPanelStateChanged(new)
    }

    private fun dispatchDismissStarted() {
        if (dismissStartedDispatched) return
        dismissStartedDispatched = true
        host.onPanelDismissStarted(state)
    }

    private fun cancelDismissStarted() {
        if (!dismissStartedDispatched) return
        dismissStartedDispatched = false
        host.onPanelDismissCancelled(state)
    }

    /** Cancel without allowing an obsolete animator's completion callback to commit stale UI. */
    private fun cancelPanelAnimation() {
        panelAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        panelAnimator = null
    }

    /**
     * Animate the panel's real layout height instead of replacing it with invisible root padding.
     * This keeps every intermediate frame visible and also makes interruption deterministic: a
     * reversed transition starts from [View.getHeight], i.e. the frame currently on screen.
     */
    private fun animatePanelHeight(fromHeight: Int, toHeight: Int, onEnd: () -> Unit) {
        if (fromHeight == toHeight || animDurationMs <= 0L) {
            setPanelHeight(toHeight)
            onEnd()
            return
        }
        val animator = ValueAnimator.ofInt(fromHeight, toHeight).apply {
            duration = animDurationMs
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                setPanelHeight(it.animatedValue as Int)
                host.onAnchorContent()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (panelAnimator !== animation) return
                    panelAnimator = null
                    onEnd()
                }
            })
        }
        panelAnimator = animator
        animator.start()
    }

    private fun visiblePanelHeight(): Int =
        if (host.panelView.isVisible) host.panelView.height.coerceAtLeast(0) else 0

    private fun occupiedPanelHeight(): Int {
        if (!host.panelView.isVisible) return 0
        return maxOf(
            host.panelView.height,
            host.panelView.layoutParams.height,
        ).coerceAtLeast(0)
    }

    private fun setPanelHeight(height: Int) {
        val clampedHeight = height.coerceAtLeast(0)
        val params = host.panelView.layoutParams
        if (params.height == clampedHeight) return
        params.height = clampedHeight
        host.panelView.layoutParams = params
    }

    private fun finishHidingPanel() {
        setPanelHeight(0)
        host.panelView.isVisible = false
        host.panelRoot.updatePadding(bottom = navBarHeight)
        setState(PanelState.NONE)
    }

    private fun finishSwitchToKeyboard(keyboardBottom: Int) {
        // Transfer the occupied height before removing the panel. This is the no-animation/OEM
        // fallback as well as the normal animation end path, so both converge on one final frame.
        host.panelRoot.updatePadding(bottom = keyboardBottom.coerceAtLeast(navBarHeight))
        setPanelHeight(0)
        host.panelView.isVisible = false
        setState(PanelState.KEYBOARD)
    }

    private fun applyPanelNavigationInset() {
        val bottom = panelBasePaddingBottom + navBarHeight
        if (host.panelView.paddingBottom != bottom) {
            host.panelView.updatePadding(bottom = bottom)
        }
    }

    private fun expandedPanelHeight(): Int = panelContentHeight() + navBarHeight

    private fun panelContentHeight(): Int =
        if (savedKeyboardHeight > 0) savedKeyboardHeight else dpToPx(fallbackHeightDp)

    private fun showKeyboard() {
        val input = host.panelInputView ?: return
        val window = hostWindow ?: return
        input.requestFocus()
        WindowCompat.getInsetsController(window, input)
            .show(WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        val input = host.panelInputView ?: return
        val window = hostWindow ?: return
        WindowCompat.getInsetsController(window, input)
            .hide(WindowInsetsCompat.Type.ime())
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            host.panelRoot.resources.displayMetrics,
        ).toInt()
}
