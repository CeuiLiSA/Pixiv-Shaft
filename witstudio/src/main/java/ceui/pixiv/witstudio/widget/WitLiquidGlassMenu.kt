package ceui.pixiv.witstudio.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.shape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Liquid Glass expanding menu anchored inside a [WitLiquidGlassLayout].
 *
 * Unlike a [android.widget.PopupWindow], this menu lives directly inside [WitLiquidGlassLayout] so
 * its surface samples and refracts the live scene beneath it. On open, the glass silhouette pours
 * out of the anchor orb as a teardrop-bridged liquid droplet (uneven capsule smooth-unioned with
 * a capsule-to-rounded-rect body in AGSL), dips with elastic gravity momentum, relaxes its corners
 * into a crisp rounded glass panel, and pulls the menu content from a Gaussian blur into focus
 * (API 31+). Dismissing smoothly reverses the liquid morph back into the anchor orb.
 */
public class WitLiquidGlassMenu private constructor(
    private val host: WitLiquidGlassLayout,
    private val anchor: View,
    private val entries: List<Entry>,
    private val config: Config,
) {

    public sealed interface Entry {
        public data class Action(
            public val label: CharSequence,
            @get:DrawableRes public val icon: Int = 0,
            public val destructive: Boolean = false,
            public val checked: Boolean = false,
            public val onClick: () -> Unit,
        ) : Entry

        public data class Toggle(
            public val label: CharSequence,
            @get:DrawableRes public val icon: Int = 0,
            public val isChecked: () -> Boolean,
            public val onToggle: (Boolean) -> Unit,
        ) : Entry

        public data object Divider : Entry
    }

    public class Builder {
        public var material: WitLiquidGlassView.Material? = null
        public var tone: WitLiquidGlassView.Tone? = null

        @get:ColorInt
        @setparam:ColorInt
        public var tintColor: Int? = null

        public var clearDimming: Float? = null
        public var cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS_DP

        @DrawableRes
        public var anchorIconRes: Int = 0
        public var onDismiss: (() -> Unit)? = null

        private val entries = mutableListOf<Entry>()

        public fun item(
            label: CharSequence,
            @DrawableRes icon: Int = 0,
            destructive: Boolean = false,
            checked: Boolean = false,
            onClick: () -> Unit,
        ) {
            entries += Entry.Action(label, icon, destructive, checked, onClick)
        }

        public fun switchItem(
            label: CharSequence,
            @DrawableRes icon: Int = 0,
            isChecked: () -> Boolean,
            onToggle: (Boolean) -> Unit,
        ) {
            entries += Entry.Toggle(label, icon, isChecked, onToggle)
        }

        public fun divider() {
            if (entries.isNotEmpty() && entries.last() !is Entry.Divider) {
                entries += Entry.Divider
            }
        }

        internal fun buildEntries(): List<Entry> {
            while (entries.isNotEmpty() && entries.last() is Entry.Divider) {
                entries.removeAt(entries.lastIndex)
            }
            return entries.toList()
        }

        internal fun buildConfig(): Config = Config(
            material = material,
            tone = tone,
            tintColor = tintColor,
            clearDimming = clearDimming,
            cornerRadiusDp = cornerRadiusDp,
            anchorIconRes = anchorIconRes,
            onDismiss = onDismiss,
        )
    }

    internal data class Config(
        val material: WitLiquidGlassView.Material?,
        val tone: WitLiquidGlassView.Tone?,
        @get:ColorInt val tintColor: Int?,
        val clearDimming: Float?,
        val cornerRadiusDp: Float,
        @get:DrawableRes val anchorIconRes: Int,
        val onDismiss: (() -> Unit)?,
    )

    private data class RowBinding(
        val view: View,
        val entry: Entry,
        val toggle: SwitchCompat? = null,
    )

    private enum class DragMode {
        IDLE,
        UNDECIDED,
        SCRUB,
        VERTICAL_SCROLL,
        HORIZONTAL_ELASTIC,
    }

    private val context: Context = host.context
    private val palette: V3Palette = V3Palette.from(context)
    private val toggles = mutableListOf<Pair<SwitchCompat, Entry.Toggle>>()
    private val interactiveRows = mutableListOf<RowBinding>()
    private var syncingToggles = false

    private var scrimView: View? = null
    private var scrimDrawable: ColorDrawable? = null
    private var menuGlass: WitLiquidGlassView? = null
    private var scrollView: ScrollView? = null
    private var rowsListView: LinearLayout? = null
    private var highlightPill: HighlightPillView? = null
    private var echoIconView: ImageView? = null
    private var firstFocusableRow: View? = null

    private enum class State {
        PENDING_LAYOUT,
        ATTACHED,
        OPENING_OR_OPEN,
        DISMISSING,
        FINISHED,
    }

    private var morphAnimator: ValueAnimator? = null
    private var pendingShowRunnable: Runnable? = null
    private var backCallback: OnBackPressedCallback? = null
    private var hostLayoutListener: View.OnLayoutChangeListener? = null
    private var detachListener: View.OnAttachStateChangeListener? = null

    private var dragSpringX: SpringAnimation? = null
    private var dragSpringY: SpringAnimation? = null
    private var dragSpringPress: SpringAnimation? = null
    private var dragPullX = 0f
    private var dragPullY = 0f
    private var dragPress = 0f
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var dragFingerDown = false
    private var openSettled = false
    private var highlightedRow: RowBinding? = null
    private var highlightBoundsAnimator: ValueAnimator? = null

    private var progress = 0f
    private var backStartProgress = 1f
    private var anchorHidden = false
    private var isBorderlessAnchor = false
    private val isNoneQuality: Boolean
        get() = (host.quality ?: WitLiquidGlassQuality.defaultQuality) == WitLiquidGlassQuality.NONE
    private var menuWidth = 0
    private var menuHeight = 0
    private var anchorLocalL = 0f
    private var anchorLocalT = 0f
    private var anchorLocalR = 0f
    private var anchorLocalB = 0f
    private var anchorWidthPx = 0f
    private var anchorHeightPx = 0f
    private var anchorCenterX = 0f
    private var anchorCenterY = 0f
    private var anchorRadiusPx = 0f
    private var endRadiusPx = 0f
    private var anchorOnRight = true
    private var expandingDown = true

    private var lastContentBlurPx = -1f
    private var lastEchoBlurPx = -1f

    @ColorInt
    private var startTint = Color.TRANSPARENT

    @ColorInt
    private var targetTint = Color.TRANSPARENT
    private var maxScrimAlpha = 0

    private var state = State.PENDING_LAYOUT

    public val isShowing: Boolean
        get() = state == State.ATTACHED || state == State.OPENING_OR_OPEN || state == State.DISMISSING

    /** Re-syncs all switch rows with their backing state (e.g. after returning from a sub-activity). */
    public fun refreshToggles() {
        if (state != State.OPENING_OR_OPEN && state != State.ATTACHED) return
        syncingToggles = true
        toggles.forEach { (toggle, entry) -> toggle.isChecked = entry.isChecked() }
        syncingToggles = false
    }

    /** Dismisses the menu, smoothly morphing it back into the anchor button when [animated] is true. */
    public fun dismiss(animated: Boolean = true) {
        when (state) {
            State.FINISHED -> return
            State.PENDING_LAYOUT, State.ATTACHED -> {
                finishAndCleanup()
                return
            }
            State.DISMISSING -> {
                if (!animated) finishAndCleanup()
                return
            }
            State.OPENING_OR_OPEN -> Unit
        }
        state = State.DISMISSING
        openSettled = false
        dragFingerDown = false
        cancelDragSprings()
        clearHighlight(animated = false)
        backCallback?.isEnabled = false
        scrimView?.isClickable = false
        setRowsInteractive(false)

        if (!animated || !motionEnabled() || progress <= 0.001f || menuWidth <= 0 || menuHeight <= 0) {
            finishAndCleanup()
            return
        }
        scrollView?.clipToOutline = false
        applyProgress(progress, closing = true)
        val duration = (CLOSE_DURATION_MS * progress.coerceIn(0.25f, 1f)).toLong()
        animateProgress(target = 0f, durationMs = duration, closing = true)
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showInternal() {
        if (state != State.PENDING_LAYOUT) return
        if (!host.isAttachedToWindow) {
            finishAndCleanup()
            return
        }
        host.setTag(R.id.wit_liquid_glass_menu_active, this)
        if (host.width <= 0 || host.height <= 0) {
            val runnable = Runnable {
                pendingShowRunnable = null
                if (state != State.PENDING_LAYOUT) return@Runnable
                if (host.isAttachedToWindow && host.width > 0 && host.height > 0) {
                    showInternal()
                } else {
                    finishAndCleanup()
                }
            }
            pendingShowRunnable = runnable
            host.post(runnable)
            return
        }

        val anchorGlass = anchor as? WitLiquidGlassView
        val hasGlassAnchor = anchorGlass != null
        val hasVisibleBackground = anchor.background != null && (anchor.background.alpha > 0)
        isBorderlessAnchor = !hasGlassAnchor && !hasVisibleBackground
        val resolvedMaterial = config.material ?: anchorGlass?.material ?: WitLiquidGlassView.Material.REGULAR
        val resolvedTone = config.tone ?: anchorGlass?.tone ?: WitLiquidGlassView.Tone.THEME
        val resolvedClearDimming = (config.clearDimming ?: anchorGlass?.clearDimming ?: 0f).coerceIn(0f, 1f)
        val isDark = when (resolvedTone) {
            WitLiquidGlassView.Tone.THEME -> palette.isDark
            WitLiquidGlassView.Tone.LIGHT -> false
            WitLiquidGlassView.Tone.DARK -> true
        }

        startTint = anchorGlass?.tintColor ?: Color.TRANSPARENT
        targetTint = when {
            config.tintColor != null -> config.tintColor
            startTint != Color.TRANSPARENT -> startTint
            resolvedMaterial == WitLiquidGlassView.Material.CLEAR -> Color.TRANSPARENT
            isDark -> DEFAULT_DARK_PANEL_TINT
            else -> DEFAULT_LIGHT_PANEL_TINT
        }
        maxScrimAlpha = if (isDark) DARK_SCRIM_ALPHA else LIGHT_SCRIM_ALPHA

        // 1) Compute safe inset bounds inside host (handles split-screen & tablet panes accurately).
        val hostLoc = IntArray(2)
        host.getLocationInWindow(hostLoc)
        val rootView = host.rootView
        val rootW = rootView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val rootH = rootView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val rawInsets = ViewCompat.getRootWindowInsets(host)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val insetLeft = ((rawInsets?.left ?: 0) - hostLoc[0]).coerceAtLeast(0)
        val insetTop = ((rawInsets?.top ?: 0) - hostLoc[1]).coerceAtLeast(0)
        val insetRight = ((rawInsets?.right ?: 0) - (rootW - (hostLoc[0] + host.width))).coerceAtLeast(0)
        val insetBottom = ((rawInsets?.bottom ?: 0) - (rootH - (hostLoc[1] + host.height))).coerceAtLeast(0)

        val safeLeft = maxOf(host.paddingLeft, insetLeft) + context.dp(EDGE_MARGIN_DP)
        val safeRight = host.width - maxOf(host.paddingRight, insetRight) - context.dp(EDGE_MARGIN_DP)
        val safeTop = maxOf(host.paddingTop, insetTop) + context.dp(TOP_MARGIN_DP)
        val safeBottom = host.height - maxOf(host.paddingBottom, insetBottom) - context.dp(BOTTOM_MARGIN_DP)
        val availableW = (safeRight - safeLeft).coerceAtLeast(context.dp(160))
        val availableH = (safeBottom - safeTop).coerceAtLeast(context.dp(120))

        // 2) Build menu glass surface and rows.
        val glass = WitLiquidGlassView(context).apply {
            isLargeSurface = true
            material = resolvedMaterial
            tone = resolvedTone
            clearDimming = resolvedClearDimming
            cornerRadiusDp = config.cornerRadiusDp
            tintColor = startTint
            isGlassInteractive = false
            isClickable = true
            isFocusable = false
            // Keep child content unclipped at laid-out bounds during gravity dip;
            // WitLiquidGlassView.dispatchDraw clips children to the exact live teardrop silhouette.
            clipChildren = false
            clipToPadding = false
            elevation = if (isBorderlessAnchor) 0f else context.dpF(MENU_ELEVATION_DP)
            platterAlpha = if (isBorderlessAnchor) 0f else 1f
        }
        val contentColor = glass.contentColor
        val dangerColor = if (isDark) DARK_DANGER_COLOR else LIGHT_DANGER_COLOR

        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padV = context.dp(CONTAINER_PAD_V_DP)
            setPadding(0, padV, 0, padV)
        }
        rowsListView = rowsContainer
        entries.forEach { entry ->
            when (entry) {
                is Entry.Action -> {
                    val row = buildActionRow(entry, contentColor, dangerColor, isDark)
                    if (firstFocusableRow == null) firstFocusableRow = row
                    interactiveRows += RowBinding(row, entry)
                    rowsContainer.addView(
                        row,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val hMargin = context.dp(ROW_HORIZONTAL_MARGIN_DP)
                            marginStart = hMargin
                            marginEnd = hMargin
                        },
                    )
                }
                is Entry.Toggle -> {
                    val (row, toggle) = buildToggleRow(entry, contentColor, isDark)
                    if (firstFocusableRow == null) firstFocusableRow = row
                    interactiveRows += RowBinding(row, entry, toggle)
                    rowsContainer.addView(
                        row,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val hMargin = context.dp(ROW_HORIZONTAL_MARGIN_DP)
                            marginStart = hMargin
                            marginEnd = hMargin
                        },
                    )
                }
                Entry.Divider -> {
                    val divider = View(context).apply {
                        setBackgroundColor(V3Palette.withAlpha(contentColor, 0.14f))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    rowsContainer.addView(
                        divider,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            context.hairlinePx(),
                        ).apply {
                            val hMargin = context.dp(DIVIDER_INSET_DP)
                            val vMargin = context.dp(5)
                            marginStart = hMargin
                            marginEnd = hMargin
                            topMargin = vMargin
                            bottomMargin = vMargin
                        },
                    )
                }
            }
        }

        // 3) Measure natural width & height cleanly (handles 200% font scale and long translations).
        val maxMenuW = minOf(context.dp(MAX_MENU_WIDTH_DP), availableW)
        val minMenuW = minOf(context.dp(MIN_MENU_WIDTH_DP), maxMenuW)
        rowsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(maxMenuW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        menuWidth = rowsContainer.measuredWidth.coerceIn(minMenuW, maxMenuW)
        rowsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxMenuH = minOf((availableH * MAX_HEIGHT_FRACTION).toInt(), context.dp(MAX_MENU_HEIGHT_DP))
            .coerceAtMost(availableH)
            .coerceAtLeast(context.dp(96))
        menuHeight = rowsContainer.measuredHeight.coerceAtMost(maxMenuH).coerceAtLeast(context.dp(56))

        // 4) Position relative to anchor (pinned to anchor edge; automatically flips if near bottom).
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val rawAnchorW = anchor.width.takeIf { it > 0 } ?: context.dp(44)
        val rawAnchorH = anchor.height.takeIf { it > 0 } ?: context.dp(44)
        val anchorW = rawAnchorW.coerceAtMost(menuWidth)
        val anchorH = rawAnchorH.coerceAtMost(menuHeight)
        val anchorL = if (anchor.width > 0) anchorLoc[0] - hostLoc[0] else safeRight - anchorW
        val anchorT = if (anchor.height > 0) anchorLoc[1] - hostLoc[1] else safeTop
        val anchorR = anchorL + rawAnchorW
        val anchorB = anchorT + rawAnchorH
        anchorOnRight = (anchorL + anchorR) * 0.5f >= host.width * 0.5f

        // Never clamp safeTop below an already-valid anchor top (prevents handoff vertical snap).
        val effectiveSafeTop = minOf(safeTop, anchorT.coerceAtLeast(insetTop))
        val effectiveSafeRight = maxOf(safeRight, anchorR.coerceAtMost(host.width - insetRight))
        val effectiveSafeLeft = minOf(safeLeft, anchorL.coerceAtLeast(insetLeft))

        val rawMenuLeft = if (anchorOnRight) anchorR - menuWidth else anchorL
        val menuLeft = rawMenuLeft.coerceIn(
            effectiveSafeLeft,
            (effectiveSafeRight - menuWidth).coerceAtLeast(effectiveSafeLeft),
        )

        val topRise = context.dp(TOP_RISE_OVER_ANCHOR_DP)
        val canExpandDown = anchorT - topRise + menuHeight <= safeBottom
        val canExpandUp = anchorB + topRise - menuHeight >= effectiveSafeTop
        expandingDown = canExpandDown || !canExpandUp
        val rawMenuTop = if (expandingDown) anchorT - topRise else anchorB + topRise - menuHeight
        val menuTop = rawMenuTop.coerceIn(
            effectiveSafeTop,
            (safeBottom - menuHeight).coerceAtLeast(effectiveSafeTop),
        )

        anchorWidthPx = anchorW.toFloat()
        anchorHeightPx = anchorH.toFloat()
        anchorLocalL = (anchorL - menuLeft).toFloat()
        anchorLocalT = (anchorT - menuTop).toFloat()
        anchorLocalR = anchorLocalL + anchorWidthPx
        anchorLocalB = anchorLocalT + anchorHeightPx
        anchorCenterX = (anchorLocalL + anchorLocalR) * 0.5f
        anchorCenterY = (anchorLocalT + anchorLocalB) * 0.5f
        anchorRadiusPx = minOf(anchorWidthPx, anchorHeightPx) * 0.5f
        endRadiusPx = minOf(context.dpF(config.cornerRadiusDp), minOf(menuWidth, menuHeight) * 0.5f)

        // 5) Wrap rows in ScrollView with zero-relayout concentric highlight pill + iOS drag physics.
        val highlightRadiusPx = context.dpF((config.cornerRadiusDp - ROW_HORIZONTAL_MARGIN_DP).coerceAtLeast(14f))
        val pill = HighlightPillView(
            context = context,
            fillColor = if (isDark) DARK_HIGHLIGHT_FILL else LIGHT_HIGHLIGHT_FILL,
            cornerRadiusPx = highlightRadiusPx,
            horizontalInsetPx = context.dpF(ROW_HORIZONTAL_MARGIN_DP.toFloat()),
        ).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        highlightPill = pill
        val scrollContent = FrameLayout(context).apply {
            addView(
                pill,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                rowsContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val scroller = createMenuScrollView(scrollContent)
        glass.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        scrollView = scroller

        // 6) Mirror anchor icon inside the anchor orb for a seamless blur-out handoff.
        val echo = createAnchorEchoView(anchor, config.anchorIconRes, contentColor)
        if (echo != null) {
            glass.addView(
                echo,
                FrameLayout.LayoutParams(anchorW, anchorH, Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = anchorLocalL.roundToInt()
                    topMargin = anchorLocalT.roundToInt()
                },
            )
            echoIconView = echo
        }

        // 7) Backdrop dismiss scrim (sibling below menuGlass so menu still samples child 0 undimmed).
        val scrimBg = ColorDrawable(Color.BLACK).apply { alpha = 0 }
        val scrim = View(context).apply {
            background = scrimBg
            elevation = context.dpF(SCRIM_ELEVATION_DP)
            outlineProvider = null
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            setOnClickListener { dismiss(animated = true) }
        }
        scrimDrawable = scrimBg
        scrimView = scrim
        menuGlass = glass

        state = State.ATTACHED

        val initialHostW = host.width
        val initialHostH = host.height
        val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width != initialHostW || v.height != initialHostH) {
                dismiss(animated = false)
            }
        }
        host.addOnLayoutChangeListener(layoutListener)
        hostLayoutListener = layoutListener

        val onDetach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                finishAndCleanup()
            }
        }
        glass.addOnAttachStateChangeListener(onDetach)
        detachListener = onDetach

        registerBackCallback()

        host.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // Physical Gravity.LEFT is intentional: menuLeft is computed from getLocationInWindow
        // and already reflects LTR/RTL anchor placement.
        host.addView(
            glass,
            FrameLayout.LayoutParams(menuWidth, menuHeight, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = menuLeft
                topMargin = menuTop
            },
        )

        anchor.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

        // Start animation in pre-draw so frame 0 renders at the exact anchor circle with zero flash.
        glass.doOnPreDraw {
            if (state != State.ATTACHED) return@doOnPreDraw
            state = State.OPENING_OR_OPEN
            hideAnchorForHandoff()
            if (!motionEnabled()) {
                applyProgress(1f, closing = false)
                settleOpen()
            } else {
                applyProgress(0f, closing = false)
                animateProgress(target = 1f, durationMs = OPEN_DURATION_MS, closing = false)
            }
        }
    }

    private fun hideAnchorForHandoff() {
        if (anchorHidden || anchor.visibility != View.VISIBLE) return
        anchorHidden = true
        (anchor as? WitLiquidGlassView)?.resetInteraction()
        anchor.visibility = View.INVISIBLE
    }

    private fun restoreAnchor() {
        if (!anchorHidden) return
        anchorHidden = false
        if (anchor.visibility == View.INVISIBLE) {
            anchor.visibility = View.VISIBLE
        }
    }

    private fun buildActionRow(
        entry: Entry.Action,
        @ColorInt contentColor: Int,
        @ColorInt dangerColor: Int,
        isDark: Boolean,
    ): LinearLayout {
        val fgColor = if (entry.destructive) dangerColor else contentColor
        val iconColor = if (entry.destructive) dangerColor else ColorUtils.setAlphaComponent(contentColor, 232)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
            setPadding(context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP), context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP))
            background = buildRowRipple(isDark)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (state == State.DISMISSING || state == State.FINISHED) return@setOnClickListener
                dismiss(animated = true)
                entry.onClick()
            }
        }
        if (entry.icon != 0) {
            val iconView = ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = ColorStateList.valueOf(iconColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(
                iconView,
                LinearLayout.LayoutParams(context.dp(ICON_SIZE_DP), context.dp(ICON_SIZE_DP)).apply {
                    marginEnd = context.dp(ICON_GAP_DP)
                },
            )
        }
        val titleView = context.label(entry.label, size = 15.5f, weight = 500, color = fgColor).apply {
            lineHeightRatio(1.3f)
        }
        // WRAP_CONTENT + weight=1 lets AT_MOST measure natural width while EXACTLY expands cleanly.
        row.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        if (entry.checked) {
            val checkView = ImageView(context).apply {
                setImageResource(R.drawable.wit_ic_dialog_mark)
                imageTintList = ColorStateList.valueOf(if (isDark) Color.WHITE else palette.textAccent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(
                checkView,
                LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply {
                    marginStart = context.dp(10)
                },
            )
        }
        return row
    }

    private fun buildToggleRow(
        entry: Entry.Toggle,
        @ColorInt contentColor: Int,
        isDark: Boolean,
    ): Pair<LinearLayout, SwitchCompat> {
        val iconColor = ColorUtils.setAlphaComponent(contentColor, 232)
        val toggle = SwitchCompat(context).apply {
            isSaveEnabled = false
            contentDescription = entry.label
            isChecked = entry.isChecked()
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            )
            thumbTintList = ColorStateList(
                states,
                if (isDark) intArrayOf(Color.WHITE, 0xFFDCDCE6.toInt())
                else intArrayOf(palette.primary, Color.WHITE),
            )
            trackTintList = ColorStateList(
                states,
                if (isDark) intArrayOf(V3Palette.withAlpha(palette.primary, 0.78f), 0x45FFFFFF)
                else intArrayOf(V3Palette.withAlpha(palette.primary, 0.55f), 0x2E1A1A2E),
            )
            setOnCheckedChangeListener { _, checked ->
                if (syncingToggles || state == State.DISMISSING || state == State.FINISHED) {
                    return@setOnCheckedChangeListener
                }
                entry.onToggle(checked)
                syncingToggles = true
                isChecked = entry.isChecked()
                syncingToggles = false
            }
        }
        toggles += toggle to entry

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
            setPadding(context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP), context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP))
            background = buildRowRipple(isDark)
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                if (state == State.DISMISSING || state == State.FINISHED) return@setOnClickListener
                toggle.performClick()
            }
        }
        if (entry.icon != 0) {
            val iconView = ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = ColorStateList.valueOf(iconColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(
                iconView,
                LinearLayout.LayoutParams(context.dp(ICON_SIZE_DP), context.dp(ICON_SIZE_DP)).apply {
                    marginEnd = context.dp(ICON_GAP_DP)
                },
            )
        }
        val titleView = context.label(entry.label, size = 15.5f, weight = 500, color = contentColor).apply {
            lineHeightRatio(1.3f)
        }
        row.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginEnd = context.dp(10)
            },
        )
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return row to toggle
    }

    private fun buildRowRipple(isDark: Boolean): StateListDrawable {
        val focusColor = if (isDark) DARK_HIGHLIGHT_FILL else LIGHT_HIGHLIGHT_FILL
        val radiusPx = context.dpF((config.cornerRadiusDp - ROW_HORIZONTAL_MARGIN_DP).coerceAtLeast(14f))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(radiusPx, focusColor))
        }
    }

    private fun createAnchorEchoView(
        anchor: View,
        @DrawableRes overrideIconRes: Int,
        @ColorInt fallbackTint: Int,
    ): ImageView? {
        if (overrideIconRes != 0) {
            val pad = context.dp(10)
            return ImageView(context).apply {
                setImageResource(overrideIconRes)
                imageTintList = ColorStateList.valueOf(fallbackTint)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val source = findAnchorImageView(anchor) ?: return null
        val drawable = source.drawable?.constantState?.newDrawable(context.resources)?.mutate() ?: return null
        return ImageView(context).apply {
            setImageDrawable(drawable)
            imageTintList = source.imageTintList
            scaleType = source.scaleType
            setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun findAnchorImageView(view: View): ImageView? {
        if (view is ImageView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findAnchorImageView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun animateProgress(
        target: Float,
        durationMs: Long,
        closing: Boolean,
    ) {
        morphAnimator?.cancel()
        val start = progress
        var canceled = false
        morphAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                applyProgress(anim.animatedValue as Float, closing)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || state == State.FINISHED) return
                    if (target <= 0f) {
                        finishAndCleanup()
                    } else {
                        applyProgress(1f, closing = false)
                        settleOpen()
                    }
                }
            })
            start()
        }
    }

    private fun settleOpen() {
        openSettled = true
        dragPullX = 0f
        dragPullY = 0f
        dragPress = 0f
        menuGlass?.let { glass ->
            glass.translationX = 0f
            glass.translationY = 0f
            glass.scaleX = 1f
            glass.scaleY = 1f
            glass.alpha = 1f
            glass.platterAlpha = 1f
            glass.elevation = context.dpF(MENU_ELEVATION_DP)
            glass.clearMorphFrame()
        }
        scrollView?.let { content ->
            content.alpha = 1f
            content.scaleX = 1f
            content.scaleY = 1f
            content.translationX = 0f
            content.translationY = 0f
            content.clipToOutline = true
            setViewBlur(content, 0f, isContent = true)
        }
        echoIconView?.let { echo ->
            echo.alpha = 0f
            setViewBlur(echo, 0f, isContent = false)
        }
        focusFirstRow()
    }

    /**
     * Interactive ScrollView implementing iOS 26 Liquid Glass menu touch physics:
     * - Press-and-scrub across rows with a sliding concentric highlight pill and selection ticks.
     * - Directional rubber-band stretching (with volume conservation & anchored edge resistance)
     *   plus live AGSL touch-point glass glow when dragging sideways or pulling past scroll edges.
     * - Smooth damped spring snap-back on release.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createMenuScrollView(scrollContent: FrameLayout): ScrollView {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val scroller = object : ScrollView(context) {
            private val glassPoint = FloatArray(2)
            private var activePointerId = MotionEvent.INVALID_POINTER_ID
            private var dragMode = DragMode.IDLE
            private var passThroughToSwitch = false
            private var interceptedFlingOnDown = false
            private var downX = 0f
            private var downY = 0f
            private var lastY = 0f
            private var lastLocalX = 0f
            private var lastLocalY = 0f
            private var overscrollY = 0f

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                val intercepted = super.onInterceptTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && intercepted) {
                    interceptedFlingOnDown = true
                }
                return intercepted
            }

            private fun mapToGlassLocal(ev: MotionEvent, ptrIndex: Int) {
                glassPoint[0] = ev.getX(ptrIndex)
                glassPoint[1] = ev.getY(ptrIndex)
                matrix.mapPoints(glassPoint)
                menuGlass?.matrix?.mapPoints(glassPoint)
            }

            private fun maxScrollRange(): Int {
                val contentH = scrollContent.height
                return (contentH - height).coerceAtLeast(0)
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (state == State.DISMISSING || state == State.FINISHED) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        activePointerId = ev.getPointerId(0)
                        mapToGlassLocal(ev, 0)
                        downX = glassPoint[0]
                        downY = glassPoint[1]
                        lastY = downY
                        lastLocalX = ev.x
                        lastLocalY = ev.y
                        overscrollY = 0f
                        interceptedFlingOnDown = false
                        dragMode = DragMode.UNDECIDED
                        passThroughToSwitch = isPointInsideAnySwitch(ev.x, ev.y)
                        if (passThroughToSwitch) {
                            return super.dispatchTouchEvent(ev)
                        }
                        val canScroll = maxScrollRange() > 0
                        if (canScroll) {
                            super.dispatchTouchEvent(ev)
                        }
                        if (interceptedFlingOnDown) {
                            // Tapping to stop an active fling must not highlight or trigger a passing row.
                            dragMode = DragMode.VERTICAL_SCROLL
                            onMenuFingerDown(downX, downY, ev.x, ev.y, highlightRow = false)
                        } else {
                            onMenuFingerDown(downX, downY, ev.x, ev.y, highlightRow = true)
                        }
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (passThroughToSwitch) return super.dispatchTouchEvent(ev)
                        val ptrIndex = ev.findPointerIndex(activePointerId)
                        if (ptrIndex < 0) return true
                        mapToGlassLocal(ev, ptrIndex)
                        val gx = glassPoint[0]
                        val gy = glassPoint[1]
                        val lx = ev.getX(ptrIndex)
                        val ly = ev.getY(ptrIndex)
                        val dx = gx - downX
                        val dy = gy - downY
                        val stepY = gy - lastY
                        lastY = gy
                        lastLocalX = lx
                        lastLocalY = ly

                        val w = menuWidth.toFloat().coerceAtLeast(1f)
                        val h = menuHeight.toFloat().coerceAtLeast(1f)
                        val overX = when {
                            gx < 0f -> gx
                            gx > w -> gx - w
                            else -> 0f
                        }
                        val overY = when {
                            gy < 0f -> gy
                            gy > h -> gy - h
                            else -> 0f
                        }
                        val maxScroll = maxScrollRange()
                        val canScroll = maxScroll > 0

                        if (dragMode == DragMode.UNDECIDED && hypot(dx, dy) > touchSlop) {
                            if (!canScroll) {
                                dragMode = DragMode.SCRUB
                            } else if (abs(dx) > abs(dy) * 1.15f) {
                                dragMode = DragMode.HORIZONTAL_ELASTIC
                                clearHighlight(animated = true)
                                sendCancelToSuper(ev)
                            } else {
                                dragMode = DragMode.VERTICAL_SCROLL
                                clearHighlight(animated = true)
                            }
                        }

                        when (dragMode) {
                            DragMode.UNDECIDED -> {
                                if (canScroll) super.dispatchTouchEvent(ev)
                                updateTouchHighlight(lx, ly, playHaptic = false)
                                updateDragTargets(gx, gy, dx * 0.70f, dy * 0.35f)
                            }
                            DragMode.SCRUB -> {
                                updateTouchHighlight(lx, ly, playHaptic = true)
                                val effX = dx * 0.95f + overX * 0.65f
                                val effY = dy * 0.42f + overY * 1.15f
                                updateDragTargets(gx, gy, effX, effY)
                            }
                            DragMode.HORIZONTAL_ELASTIC -> {
                                val effX = dx * 1.0f + overX * 0.65f
                                val effY = dy * 0.48f + overY * 1.10f
                                updateDragTargets(gx, gy, effX, effY)
                            }
                            DragMode.VERTICAL_SCROLL -> {
                                val atTop = scrollY <= 0
                                val atBottom = scrollY >= maxScroll
                                val enteringOverscroll =
                                    overscrollY != 0f || (atTop && stepY > 0f) || (atBottom && stepY < 0f)
                                if (enteringOverscroll) {
                                    val prev = overscrollY
                                    val next = prev + stepY
                                    if (prev > 0f && next <= 0f || prev < 0f && next >= 0f) {
                                        overscrollY = 0f
                                        super.dispatchTouchEvent(ev)
                                    } else {
                                        overscrollY = next
                                    }
                                } else {
                                    super.dispatchTouchEvent(ev)
                                }
                                val effX = dx * 0.55f + overX * 0.80f
                                val effY = overscrollY * 1.15f + overY * 0.85f
                                updateDragTargets(gx, gy, effX, effY)
                            }
                            DragMode.IDLE -> Unit
                        }
                        return true
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (passThroughToSwitch) return super.dispatchTouchEvent(ev)
                        if (maxScrollRange() > 0 && (dragMode == DragMode.UNDECIDED || dragMode == DragMode.VERTICAL_SCROLL)) {
                            super.dispatchTouchEvent(ev)
                        }
                        return true
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (passThroughToSwitch) return super.dispatchTouchEvent(ev)
                        if (maxScrollRange() > 0 && (dragMode == DragMode.UNDECIDED || dragMode == DragMode.VERTICAL_SCROLL)) {
                            super.dispatchTouchEvent(ev)
                        }
                        if (ev.getPointerId(ev.actionIndex) == activePointerId) {
                            val oldIndex = ev.actionIndex
                            mapToGlassLocal(ev, oldIndex)
                            val oldGx = glassPoint[0]
                            val oldGy = glassPoint[1]
                            val newIndex = if (oldIndex == 0) 1 else 0
                            if (newIndex < ev.pointerCount) {
                                activePointerId = ev.getPointerId(newIndex)
                                mapToGlassLocal(ev, newIndex)
                                downX += glassPoint[0] - oldGx
                                downY += glassPoint[1] - oldGy
                                lastY = glassPoint[1]
                                lastLocalX = ev.getX(newIndex)
                                lastLocalY = ev.getY(newIndex)
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (passThroughToSwitch) {
                            passThroughToSwitch = false
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            dragMode = DragMode.IDLE
                            return super.dispatchTouchEvent(ev)
                        }
                        val ptrIndex = ev.findPointerIndex(activePointerId)
                        val lx = if (ptrIndex >= 0) ev.getX(ptrIndex) else lastLocalX
                        val ly = if (ptrIndex >= 0) ev.getY(ptrIndex) else lastLocalY
                        val canScroll = maxScrollRange() > 0
                        val shouldCommitRow =
                            dragMode == DragMode.UNDECIDED || dragMode == DragMode.SCRUB
                        val hit = if (shouldCommitRow) {
                            updateTouchHighlight(lx, ly, playHaptic = false)
                            highlightedRow
                        } else {
                            null
                        }
                        if (canScroll) {
                            if (dragMode == DragMode.VERTICAL_SCROLL && overscrollY == 0f) {
                                super.dispatchTouchEvent(ev)
                            } else {
                                sendCancelToSuper(ev)
                            }
                        }
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        dragMode = DragMode.IDLE
                        overscrollY = 0f
                        onMenuFingerUp(hit)
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        val wasSwitch = passThroughToSwitch
                        passThroughToSwitch = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        dragMode = DragMode.IDLE
                        overscrollY = 0f
                        if (wasSwitch || maxScrollRange() > 0) {
                            super.dispatchTouchEvent(ev)
                        }
                        onMenuFingerUp(commitRow = null)
                        return true
                    }

                    else -> return if (passThroughToSwitch) super.dispatchTouchEvent(ev) else true
                }
            }

            private fun sendCancelToSuper(source: MotionEvent) {
                val cancel = MotionEvent.obtain(source)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }

            private fun isPointInsideAnySwitch(x: Float, y: Float): Boolean {
                val contentY = y + scrollY
                for (binding in interactiveRows) {
                    val sw = binding.toggle ?: continue
                    val row = binding.view
                    val swLeft = row.left + sw.left
                    val swTop = row.top + sw.top
                    val swRight = swLeft + sw.width
                    val swBottom = swTop + sw.height
                    if (x >= swLeft && x <= swRight && contentY >= swTop && contentY <= swBottom) {
                        return true
                    }
                }
                return false
            }
        }
        return scroller.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(24))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, endRadiusPx)
                }
            }
            clipToOutline = false
            pivotX = menuWidth * 0.5f
            pivotY = menuHeight * 0.5f
            addView(
                scrollContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun onMenuFingerDown(
        glassX: Float,
        glassY: Float,
        localX: Float,
        localY: Float,
        highlightRow: Boolean,
    ) {
        dragFingerDown = true
        dragTouchX = glassX
        dragTouchY = glassY
        if (highlightRow) {
            updateTouchHighlight(localX, localY, playHaptic = false)
        } else {
            clearHighlight(animated = false)
        }
        if (!openSettled || !motionEnabled()) return
        scrollView?.clipToOutline = false
        animateDragSpring(targetX = 0f, targetY = 0f, targetPress = 1f, releasing = false)
    }

    private fun updateDragTargets(x: Float, y: Float, pullX: Float, pullY: Float) {
        dragTouchX = x
        dragTouchY = y
        if (!openSettled || !motionEnabled() || isNoneQuality) return
        scrollView?.clipToOutline = false
        animateDragSpring(targetX = pullX, targetY = pullY, targetPress = 1f, releasing = false)
        applyDragDeform()
    }

    private fun onMenuFingerUp(commitRow: RowBinding?) {
        dragFingerDown = false
        if (commitRow != null) {
            when (val entry = commitRow.entry) {
                is Entry.Action -> {
                    clearHighlight(animated = false)
                    dismiss(animated = true)
                    entry.onClick()
                    return
                }
                is Entry.Toggle -> {
                    commitRow.toggle?.performClick()
                }
                Entry.Divider -> Unit
            }
        }
        clearHighlight(animated = true)
        if (openSettled && motionEnabled()) {
            animateDragSpring(targetX = 0f, targetY = 0f, targetPress = 0f, releasing = true)
        }
    }

    private fun animateDragSpring(
        targetX: Float,
        targetY: Float,
        targetPress: Float,
        releasing: Boolean,
    ) {
        val sx = dragSpringX ?: createDragSpring(minVisible = 0.5f) { value ->
            dragPullX = value
            applyDragDeform()
        }.also { dragSpringX = it }
        val sy = dragSpringY ?: createDragSpring(minVisible = 0.5f) { value ->
            dragPullY = value
            applyDragDeform()
        }.also { dragSpringY = it }
        val sp = dragSpringPress ?: createDragSpring(minVisible = 0.005f) { value ->
            dragPress = value.coerceIn(0f, 1.25f)
            applyDragDeform()
        }.also { dragSpringPress = it }

        val posStiffness = if (releasing) DRAG_SPRING_RELEASE_STIFFNESS else DRAG_SPRING_FOLLOW_STIFFNESS
        val posDamping = if (releasing) DRAG_SPRING_RELEASE_DAMPING else DRAG_SPRING_FOLLOW_DAMPING
        sx.spring.stiffness = posStiffness
        sx.spring.dampingRatio = posDamping
        sy.spring.stiffness = posStiffness
        sy.spring.dampingRatio = posDamping
        sp.spring.stiffness = if (releasing) 460f else 650f
        sp.spring.dampingRatio = if (releasing) 0.78f else 0.88f

        if (!sx.isRunning) sx.setStartValue(dragPullX)
        if (!sy.isRunning) sy.setStartValue(dragPullY)
        if (!sp.isRunning) sp.setStartValue(dragPress)
        sx.animateToFinalPosition(targetX)
        sy.animateToFinalPosition(targetY)
        sp.animateToFinalPosition(targetPress)
    }

    private fun createDragSpring(
        minVisible: Float,
        onUpdate: (Float) -> Unit,
    ): SpringAnimation = SpringAnimation(FloatValueHolder(), 0f).apply {
        setMinimumVisibleChange(minVisible)
        addUpdateListener { _, value, _ -> onUpdate(value) }
        addEndListener { _, canceled, _, _ ->
            if (!canceled) maybeSettleAfterDrag()
        }
    }

    private fun applyDragDeform() {
        if (!openSettled || state != State.OPENING_OR_OPEN) return
        val glass = menuGlass ?: return
        val w = menuWidth.toFloat()
        val h = menuHeight.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = context.resources.displayMetrics.density
        val deform = computeDragDeform(
            width = w,
            height = h,
            endRadius = endRadiusPx,
            density = density,
            pullX = dragPullX,
            pullY = dragPullY,
            pressPresence = dragPress,
            patchMargin = context.dpF(MAX_MORPH_PATCH_OVERSHOOT_DP),
        )
        glass.translationX = deform.translateX
        glass.translationY = deform.translateY
        glass.setMorphFrame(deform.left, deform.top, deform.right, deform.bottom, deform.cornerRadius)
        glass.setMorphTouch(
            x = (dragTouchX - deform.translateX).coerceIn(-w * 0.25f, w * 1.25f),
            y = (dragTouchY - deform.translateY).coerceIn(-h * 0.25f, h * 1.25f),
            illumination = (dragPress.coerceIn(0f, 1f) * DRAG_TOUCH_GLOW_STRENGTH),
        )
        scrollView?.let { content ->
            val curW = deform.right - deform.left
            val curH = deform.bottom - deform.top
            content.scaleX = (curW / w).coerceIn(0.88f, 1.12f)
            content.scaleY = (curH / h).coerceIn(0.88f, 1.12f)
            content.translationX = (deform.left + deform.right) * 0.5f - w * 0.5f
            content.translationY = (deform.top + deform.bottom) * 0.5f - h * 0.5f
        }
    }

    private fun maybeSettleAfterDrag() {
        if (!openSettled || dragFingerDown || state != State.OPENING_OR_OPEN) return
        if (dragSpringX?.isRunning == true || dragSpringY?.isRunning == true || dragSpringPress?.isRunning == true) {
            return
        }
        if (abs(dragPullX) > 0.5f || abs(dragPullY) > 0.5f || abs(dragPress) > 0.01f) return
        dragPullX = 0f
        dragPullY = 0f
        dragPress = 0f
        menuGlass?.let { glass ->
            glass.translationX = 0f
            glass.translationY = 0f
            glass.clearMorphFrame()
        }
        scrollView?.let { content ->
            content.scaleX = 1f
            content.scaleY = 1f
            content.translationX = 0f
            content.translationY = 0f
            content.clipToOutline = true
        }
    }

    private fun cancelDragSprings() {
        dragSpringX?.cancel()
        dragSpringY?.cancel()
        dragSpringPress?.cancel()
    }

    private fun updateTouchHighlight(x: Float, y: Float, playHaptic: Boolean) {
        val scroller = scrollView ?: return
        val corridorPadX = context.dpF(SCRUB_CORRIDOR_X_DP)
        val corridorPadY = context.dpF(SCRUB_CORRIDOR_Y_DP)
        val inCorridor = x >= -corridorPadX && x <= menuWidth + corridorPadX &&
            y >= -corridorPadY && y <= menuHeight + corridorPadY
        val found = if (inCorridor) {
            val contentY = y + scroller.scrollY
            interactiveRows.firstOrNull { binding ->
                val row = binding.view
                contentY >= row.top && contentY <= row.bottom
            }
        } else {
            null
        }
        if (found === highlightedRow) return
        val previous = highlightedRow
        highlightedRow = found
        if (found == null) {
            clearHighlight(animated = true)
            return
        }
        if (playHaptic && previous != null) {
            scroller.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        moveHighlightPillTo(found.view, instantPosition = previous == null)
    }

    private fun moveHighlightPillTo(row: View, instantPosition: Boolean) {
        val pill = highlightPill ?: return
        val targetTop = row.top.toFloat()
        val targetHeight = row.height.coerceAtLeast(context.dp(ROW_MIN_HEIGHT_DP)).toFloat()
        pill.animate().cancel()
        highlightBoundsAnimator?.cancel()

        val animateMotion = motionEnabled() && !instantPosition
        if (!animateMotion) {
            pill.setPillBounds(targetTop, targetHeight)
            if (motionEnabled()) {
                pill.animate().alpha(1f).setDuration(80L).start()
            } else {
                pill.alpha = 1f
            }
            return
        }

        val startTop = pill.pillTop
        val startHeight = pill.pillHeight.takeIf { it > 0f } ?: targetHeight
        pill.animate().alpha(1f).setDuration(80L).start()
        highlightBoundsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 105L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                pill.setPillBounds(
                    lerp(startTop, targetTop, f),
                    lerp(startHeight, targetHeight, f),
                )
            }
            start()
        }
    }

    private fun clearHighlight(animated: Boolean) {
        highlightedRow = null
        val pill = highlightPill ?: return
        pill.animate().cancel()
        highlightBoundsAnimator?.cancel()
        if (animated && motionEnabled()) {
            pill.animate().alpha(0f).setDuration(110L).start()
        } else {
            pill.alpha = 0f
        }
    }

    private class HighlightPillView(
        context: Context,
        @ColorInt fillColor: Int,
        private val cornerRadiusPx: Float,
        private val horizontalInsetPx: Float,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        var pillTop: Float = 0f
            private set
        var pillHeight: Float = 0f
            private set

        fun setPillBounds(top: Float, height: Float) {
            if (pillTop == top && pillHeight == height) return
            pillTop = top
            pillHeight = height
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (pillHeight <= 0f || width <= horizontalInsetPx * 2f) return
            canvas.drawRoundRect(
                horizontalInsetPx,
                pillTop,
                width - horizontalInsetPx,
                pillTop + pillHeight,
                cornerRadiusPx,
                cornerRadiusPx,
                paint,
            )
        }
    }

    /**
     * Evaluates the liquid teardrop morph at normalized progress [rawP] in `0..1` (calibrated 1:1
     * against frame-by-frame reference telemetry):
     * - `0.00..0.44`: anchor orb pours down-left into a diagonal teardrop (S-curve width/height
     *   expansion + height-invariant `~34dp` top sag + AGSL uneven-capsule smooth-union `smin`);
     *   menu content emerges Gaussian-blurred inside the growing droplet.
     * - `0.44..0.78`: plump full-capsule stadium holds roundness through `p=0.55` before smoothly
     *   relaxing its corners to `30dp`, rising cleanly into place with a `~3%` elastic overshoot
     *   while Gaussian blur melts to `0dp` (razor sharp).
     * - `0.78..1.00`: crisp elastic settle into final resting bounds.
     */
    private fun applyProgress(rawP: Float, closing: Boolean) {
        val glass = menuGlass ?: return
        val p = rawP.coerceIn(0f, 1f)
        progress = p

        val w = menuWidth.toFloat()
        val h = menuHeight.toFloat()
        val maxOverW = minOf(w * 0.030f, context.dpF(MAX_WIDTH_OVERSHOOT_DP))
        val maxOverH = minOf(h * 0.030f, context.dpF(MAX_HEIGHT_OVERSHOOT_DP))
        val maxTopSag = minOf(anchorHeightPx * 0.78f, context.dpF(MAX_TOP_SAG_DP))

        // Reference S-curve growth (width leads slightly so tall menus stay plump and teardrop-shaped).
        val wProg = smoothstep(0f, 0.56f, p)
        val hProg = smoothstep(0f, 0.62f, p)
        val wOver = if (closing) 0f else maxOverW * sin(PI * ((p - 0.54f) / 0.46f).coerceIn(0f, 1f)).toFloat().pow(1.5f)
        val hOver = if (closing) 0f else maxOverH * sin(PI * ((p - 0.52f) / 0.48f).coerceIn(0f, 1f)).toFloat().pow(1.5f)
        val curW = lerp(anchorWidthPx, w, wProg) + wOver
        val curH = lerp(anchorHeightPx, h, hProg) + hOver

        // Horizontal: anchor edge stays pinned (tiny 1.5dp inward tuck during teardrop pull).
        // Clamped within patch inset margin so mid-screen/constrained anchors never clip.
        val patchMargin = context.dpF(MAX_MORPH_PATCH_OVERSHOOT_DP)
        val edgeTuck = if (closing) 0f else context.dpF(1.5f) * sin(PI * (p / 0.65f).coerceIn(0f, 1f)).toFloat()
        val rawL = if (anchorOnRight) {
            lerp(anchorLocalR, w, smoothstep(0f, 0.45f, p)) - edgeTuck - curW
        } else {
            lerp(anchorLocalL, 0f, smoothstep(0f, 0.45f, p)) + edgeTuck
        }
        val curL = clampMorphStart(rawL, curW, w, patchMargin)
        val curR = curL + curW

        // Vertical: height-invariant sag (~34dp max, 0.78x anchor height) creates the exact 35-deg
        // diagonal teardrop without stretching down the screen on tall 14-item menus.
        val sagScale = if (closing) 0.72f else 1f
        val topSag = maxTopSag * sagScale * sin(PI * (p / 0.78f).coerceIn(0f, 1f)).toFloat().pow(1.25f)
        val rawT = if (expandingDown) {
            lerp(anchorLocalT, 0f, smoothstep(0.36f, 0.84f, p)) + topSag
        } else {
            lerp(anchorLocalB, h, smoothstep(0.36f, 0.84f, p)) - topSag - curH
        }
        val curT = clampMorphStart(rawT, curH, h, patchMargin)
        val curB = curT + curH

        // When dismissing straight from an active drag/press, blend the residual elastic offset
        // smoothly to zero over the opening phase of the close morph so there is zero frame snap.
        val dragCarry = if (closing && (abs(dragPullX) > 0.5f || abs(dragPullY) > 0.5f || dragPress > 0.01f)) {
            smoothstep(0.65f, 1f, p)
        } else {
            0f
        }
        val finalL: Float
        val finalT: Float
        val finalR: Float
        val finalB: Float
        if (dragCarry > 0.001f) {
            val dragDeform = computeDragDeform(
                width = w,
                height = h,
                endRadius = endRadiusPx,
                density = context.resources.displayMetrics.density,
                pullX = dragPullX * dragCarry,
                pullY = dragPullY * dragCarry,
                pressPresence = dragPress * dragCarry,
                patchMargin = patchMargin,
            )
            glass.translationX = dragDeform.translateX
            glass.translationY = dragDeform.translateY
            val dL = dragDeform.left
            val dT = dragDeform.top
            val dR = dragDeform.right - w
            val dB = dragDeform.bottom - h
            val mergedW = (curR - curL + dR - dL).coerceAtLeast(anchorWidthPx)
            val mergedH = (curB - curT + dB - dT).coerceAtLeast(anchorHeightPx)
            finalL = clampMorphStart(curL + dL, mergedW, w, patchMargin)
            finalR = finalL + mergedW
            finalT = clampMorphStart(curT + dT, mergedH, h, patchMargin)
            finalB = finalT + mergedH
        } else {
            glass.translationX = 0f
            glass.translationY = 0f
            finalL = curL
            finalT = curT
            finalR = curR
            finalB = curB
        }

        // Corner radius: stays plump full-capsule/stadium through p=0.50, then crisp-relaxes to endRadiusPx.
        val finalW = finalR - finalL
        val finalH = finalB - finalT
        val capsuleR = minOf(finalW, finalH) * 0.5f
        val relax = smoothstep(0.50f, 0.78f, p)
        val bodyR = lerp(capsuleR, minOf(endRadiusPx, capsuleR), relax)

        // Teardrop shoulder (uneven capsule pinned to upper belly so tail proportions stay height-invariant).
        val tailWeight = 1f - smoothstep(0.26f, 0.54f, p)
        val bodyCx = (finalL + finalR) * 0.5f
        val bodyCy = (finalT + finalB) * 0.5f
        if (tailWeight > 0.001f && p > 0.001f) {
            val bellyR = minOf(finalW, finalH) * 0.48f
            val bellyCx = bodyCx
            val bellyCy = if (expandingDown) finalT + bellyR * 1.04f else finalB - bellyR * 1.04f
            val pull = smoothstep(0.22f, 0.54f, p)
            val targetTipX = if (anchorOnRight) finalR - bodyR else finalL + bodyR
            val targetTipY = if (expandingDown) finalT + bodyR else finalB - bodyR
            val tipX = lerp(anchorCenterX, targetTipX, pull)
            val tipY = lerp(anchorCenterY, targetTipY, pull)
            val taper = lerp(1f, 0.72f, sin(PI * (p / 0.52f).coerceIn(0f, 1f)).toFloat())
            val tipR = lerp(anchorRadiusPx * 0.90f, bodyR * 0.82f, pull) * taper
            val blendK = context.dpF(TEARDROP_BLEND_DP) * smoothstep(0f, 0.12f, p) * tailWeight
            glass.setMorphFrame(
                finalL, finalT, finalR, finalB, bodyR,
                tailTipX = tipX,
                tailTipY = tipY,
                tailTipRadius = tipR,
                tailBellyX = bellyCx,
                tailBellyY = bellyCy,
                tailBellyRadius = bellyR,
                tailBlendPx = blendK,
            )
        } else {
            glass.setMorphFrame(finalL, finalT, finalR, finalB, bodyR)
        }
        glass.setMorphTouch(
            x = (dragTouchX - glass.translationX).coerceIn(-w * 0.25f, w * 1.25f),
            y = (dragTouchY - glass.translationY).coerceIn(-h * 0.25f, h * 1.25f),
            illumination = dragPress.coerceIn(0f, 1f) * dragCarry * DRAG_TOUCH_GLOW_STRENGTH,
        )

        // Ramp readability tint swiftly so the droplet keeps rich glass contrast over bright artwork.
        glass.tintColor = ColorUtils.blendARGB(startTint, targetTint, smoothstep(0f, 0.25f, p))

        glass.platterAlpha = computePlatterAlpha(p, isBorderlessAnchor)
        glass.elevation = context.dpF(MENU_ELEVATION_DP) * computeElevationFraction(p, isBorderlessAnchor)

        // Menu content tracks Body B's live center/size and pulls from Gaussian blur into crisp focus.
        scrollView?.let { content ->
            val sx = (finalW / w).coerceIn(0.32f, 1.12f)
            val sy = (finalH / h).coerceIn(0.32f, 1.12f)
            val uni = minOf(sx, sy)
            content.scaleX = lerp(uni, sx, 0.25f)
            content.scaleY = lerp(uni, sy, 0.25f)
            content.translationX = bodyCx - w * 0.5f
            content.translationY = bodyCy - h * 0.5f
            content.alpha = smoothstep(0.05f, 0.36f, p)

            val blurFactor = (1f - smoothstep(0.08f, 0.64f, p)).pow(1.6f)
            setViewBlur(content, context.dpF(CONTENT_MAX_BLUR_DP) * blurFactor, isContent = true)
        }

        // Anchor icon dissolves with scale + Gaussian blur inside the top-right orb.
        echoIconView?.let { echo ->
            val echoPhase = smoothstep(0f, 0.15f, p)
            echo.alpha = 1f - echoPhase
            val echoScale = lerp(1f, 0.62f, echoPhase)
            echo.scaleX = echoScale
            echo.scaleY = echoScale
            val echoBlur = if (echoPhase in 0.01f..0.99f) context.dpF(ECHO_MAX_BLUR_DP) * echoPhase else 0f
            setViewBlur(echo, echoBlur, isContent = false)
        }

        scrimDrawable?.alpha = (maxScrimAlpha * smoothstep(0f, 0.55f, p)).roundToInt().coerceIn(0, 255)
    }

    private fun setViewBlur(view: View, blurPx: Float, isContent: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val quantized = if (blurPx < 0.5f) 0f else (blurPx * 2f).roundToInt() * 0.5f
        val prev = if (isContent) lastContentBlurPx else lastEchoBlurPx
        if (prev == quantized) return
        if (isContent) lastContentBlurPx = quantized else lastEchoBlurPx = quantized
        view.setRenderEffect(
            if (quantized > 0f) {
                RenderEffect.createBlurEffect(quantized, quantized, Shader.TileMode.CLAMP)
            } else {
                null
            },
        )
    }

    private fun setRowsInteractive(enabled: Boolean) {
        val list = rowsListView ?: return
        for (i in 0 until list.childCount) {
            list.getChildAt(i).isEnabled = enabled
        }
        toggles.forEach { (toggle, _) -> toggle.isEnabled = enabled }
    }

    private fun focusFirstRow() {
        firstFocusableRow?.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun registerBackCallback() {
        val owner = host.findViewTreeOnBackPressedDispatcherOwner() ?: findBackOwner(context) ?: return
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (state != State.OPENING_OR_OPEN) return
                openSettled = false
                dragFingerDown = false
                cancelDragSprings()
                clearHighlight(animated = false)
                morphAnimator?.cancel()
                backStartProgress = progress.coerceAtLeast(0.05f)
                scrollView?.clipToOutline = false
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (state != State.OPENING_OR_OPEN) return
                val raw = backEvent.progress.coerceIn(0f, 1f)
                val eased = 1f - (1f - raw) * (1f - raw)
                applyProgress((backStartProgress * (1f - eased * 0.42f)).coerceAtLeast(0f), closing = true)
            }

            override fun handleOnBackCancelled() {
                if (state != State.OPENING_OR_OPEN) return
                dragPullX = 0f
                dragPullY = 0f
                dragPress = 0f
                if (!motionEnabled()) {
                    applyProgress(1f, closing = false)
                    settleOpen()
                } else {
                    animateProgress(target = 1f, durationMs = 210L, closing = true)
                }
            }

            override fun handleOnBackPressed() {
                dismiss(animated = true)
            }
        }
        owner.onBackPressedDispatcher.addCallback(callback)
        backCallback = callback
    }

    private fun findBackOwner(ctx: Context): OnBackPressedDispatcherOwner? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is OnBackPressedDispatcherOwner) return current
            current = current.baseContext
        }
        return current as? OnBackPressedDispatcherOwner
    }

    private fun finishAndCleanup() {
        if (state == State.FINISHED) return
        state = State.FINISHED
        openSettled = false
        dragFingerDown = false
        pendingShowRunnable?.let { host.removeCallbacks(it) }
        pendingShowRunnable = null
        cancelDragSprings()
        dragSpringX = null
        dragSpringY = null
        dragSpringPress = null
        highlightPill?.animate()?.cancel()
        highlightBoundsAnimator?.cancel()
        highlightBoundsAnimator = null
        morphAnimator?.cancel()
        morphAnimator = null

        backCallback?.remove()
        backCallback = null

        hostLayoutListener?.let { host.removeOnLayoutChangeListener(it) }
        hostLayoutListener = null

        scrollView?.let { setViewBlur(it, 0f, isContent = true) }
        echoIconView?.let { setViewBlur(it, 0f, isContent = false) }

        val glass = menuGlass
        if (glass != null) {
            detachListener?.let { glass.removeOnAttachStateChangeListener(it) }
            glass.translationX = 0f
            glass.translationY = 0f
            glass.clearMorphFrame()
            if (glass.parent === host) host.removeView(glass)
        }
        detachListener = null
        menuGlass = null

        val scrim = scrimView
        if (scrim != null && scrim.parent === host) {
            host.removeView(scrim)
        }
        scrimView = null
        scrimDrawable = null
        scrollView = null
        rowsListView = null
        highlightPill = null
        highlightedRow = null
        echoIconView = null
        firstFocusableRow = null
        toggles.clear()
        interactiveRows.clear()

        if (host.getTag(R.id.wit_liquid_glass_menu_active) === this) {
            host.setTag(R.id.wit_liquid_glass_menu_active, null)
        }
        restoreAnchor()
        if (anchor.isAttachedToWindow) {
            anchor.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        config.onDismiss?.invoke()
    }

    public companion object {
        private const val DEFAULT_CORNER_RADIUS_DP = 30f
        private const val MIN_MENU_WIDTH_DP = 212
        private const val MAX_MENU_WIDTH_DP = 272
        private const val MAX_MENU_HEIGHT_DP = 480
        private const val MAX_HEIGHT_FRACTION = 0.72f
        private const val EDGE_MARGIN_DP = 12
        private const val TOP_MARGIN_DP = 6
        private const val BOTTOM_MARGIN_DP = 16
        private const val TOP_RISE_OVER_ANCHOR_DP = 6
        private const val CONTAINER_PAD_V_DP = 10
        private const val ROW_HORIZONTAL_MARGIN_DP = 8
        private const val ROW_PAD_H_DP = 14
        private const val ROW_PAD_V_DP = 12
        private const val ROW_MIN_HEIGHT_DP = 50
        private const val ICON_SIZE_DP = 22
        private const val ICON_GAP_DP = 14
        private const val DIVIDER_INSET_DP = 18
        private const val SCRIM_ELEVATION_DP = 16f
        private const val MENU_ELEVATION_DP = 18f

        private const val OPEN_DURATION_MS = 400L
        private const val CLOSE_DURATION_MS = 300L
        private const val MAX_TOP_SAG_DP = 34f
        private const val MAX_WIDTH_OVERSHOOT_DP = 8f
        private const val MAX_HEIGHT_OVERSHOOT_DP = 10f
        /** Strictly inside [WitLiquidGlassBudget.inset] (30dp) minus the 3dp large-surface scatter blur. */
        private const val MAX_MORPH_PATCH_OVERSHOOT_DP = 26f
        private const val TEARDROP_BLEND_DP = 10f
        private const val CONTENT_MAX_BLUR_DP = 18f
        private const val ECHO_MAX_BLUR_DP = 10f

        private const val DARK_SCRIM_ALPHA = 38
        private const val LIGHT_SCRIM_ALPHA = 22
        private const val DARK_HIGHLIGHT_FILL = 0x24FFFFFF
        private const val LIGHT_HIGHLIGHT_FILL = 0x181A1A2E
        private const val DEFAULT_DARK_PANEL_TINT = 0x4212131A
        private const val DEFAULT_LIGHT_PANEL_TINT = 0x58F6F4FB
        private const val DARK_DANGER_COLOR = 0xFFFF6B70.toInt()
        private const val LIGHT_DANGER_COLOR = 0xFFD9383E.toInt()

        private const val SCRUB_CORRIDOR_X_DP = 24f
        private const val SCRUB_CORRIDOR_Y_DP = 20f
        private const val DRAG_TOUCH_GLOW_STRENGTH = 0.62f
        private const val DRAG_SPRING_FOLLOW_STIFFNESS = 720f
        private const val DRAG_SPRING_FOLLOW_DAMPING = 0.88f
        private const val DRAG_SPRING_RELEASE_STIFFNESS = 320f
        private const val DRAG_SPRING_RELEASE_DAMPING = 0.52f

        internal data class DragDeform(
            val translateX: Float,
            val translateY: Float,
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
            val cornerRadius: Float,
        )

        /**
         * Opens a Liquid Glass morphing menu from [anchor] inside [host].
         * If the same anchor's menu is already open, tapping toggles it closed smoothly.
         */
        public fun show(
            host: WitLiquidGlassLayout,
            anchor: View,
            block: Builder.() -> Unit,
        ): WitLiquidGlassMenu? {
            val existing = host.getTag(R.id.wit_liquid_glass_menu_active) as? WitLiquidGlassMenu
            if (existing != null && existing.state != State.FINISHED) {
                val sameAnchor = existing.anchor === anchor
                if (sameAnchor && (existing.state == State.ATTACHED || existing.state == State.OPENING_OR_OPEN)) {
                    existing.dismiss(animated = true)
                    return null
                }
                existing.dismiss(animated = false)
            }

            val builder = Builder().apply(block)
            val entries = builder.buildEntries()
            if (entries.none { it is Entry.Action || it is Entry.Toggle }) return null

            return WitLiquidGlassMenu(host, anchor, entries, builder.buildConfig())
                .apply { showInternal() }
                .takeUnless { it.state == State.FINISHED }
        }

        internal fun clampMorphStart(start: Float, span: Float, limit: Float, margin: Float): Float {
            val minStart = -margin
            val maxStart = (limit + margin - span).coerceAtLeast(minStart)
            return start.coerceIn(minStart, maxStart)
        }

        internal fun rubberBand(delta: Float, maxOutput: Float, dimension: Float): Float {
            val mag = abs(delta)
            if (mag <= 0.0001f || maxOutput <= 0f || dimension <= 0f) return 0f
            val mapped = maxOutput * (1f - 1f / (1f + mag / dimension))
            return if (delta >= 0f) mapped else -mapped
        }

        /**
         * Evaluates restrained iOS 26 rubber-band elastic deformation for an open glass menu:
         * directional stretch along the pull axis, subtle orthogonal volume-conserving compression,
         * anchored far-edge resistance, and strict clamping inside [patchMargin].
         */
        internal fun computeDragDeform(
            width: Float,
            height: Float,
            endRadius: Float,
            density: Float,
            pullX: Float,
            pullY: Float,
            pressPresence: Float,
            patchMargin: Float,
        ): DragDeform {
            val dimX = maxOf(width * 0.48f, 95f * density)
            val dimY = maxOf(height * 0.42f, 110f * density)
            val rx = rubberBand(pullX, 1f, dimX)
            val ry = rubberBand(pullY, 1f, dimY)
            val ax = abs(rx)
            val ay = abs(ry)

            val maxShiftX = 22f * density
            val maxShiftY = 26f * density
            val maxStretchX = minOf(width * 0.080f, 22f * density)
            val maxStretchY = minOf(height * 0.075f, 28f * density)
            val pullMag = (ax + ay).coerceIn(0f, 1f)
            val swell = 2.6f * density * pressPresence.coerceIn(0f, 1f) * (1f - pullMag)

            val deltaW = maxStretchX * ax - maxStretchX * 0.46f * ay + swell
            val deltaH = maxStretchY * ay - maxStretchY * 0.42f * ax + swell
            val curW = (width + deltaW).coerceAtLeast(width * 0.88f)
            val curH = (height + deltaH).coerceAtLeast(height * 0.88f)

            val translateX = maxShiftX * rx
            val translateY = maxShiftY * ry
            val cx = width * 0.5f + rx * (deltaW * 0.28f)
            val cy = height * 0.5f + ry * (deltaH * 0.28f)
            val left = clampMorphStart(cx - curW * 0.5f, curW, width, patchMargin)
            val top = clampMorphStart(cy - curH * 0.5f, curH, height, patchMargin)
            val radius = (endRadius + (ax + ay) * 2.2f * density).coerceIn(0f, minOf(curW, curH) * 0.5f)
            return DragDeform(translateX, translateY, left, top, left + curW, top + curH, radius)
        }

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction

        internal fun computePlatterAlpha(progress: Float, isBorderless: Boolean): Float =
            if (isBorderless) smoothstep(0f, 0.28f, progress.coerceIn(0f, 1f)) else 1f

        internal fun computeElevationFraction(progress: Float, isBorderless: Boolean): Float =
            if (isBorderless) smoothstep(0.06f, 0.38f, progress.coerceIn(0f, 1f)) else 1f

        private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
