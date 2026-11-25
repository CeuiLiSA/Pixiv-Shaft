package ceui.pixiv.witstudio.widget

import android.app.Activity
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
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
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
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.shape.MaterialShapeDrawable
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
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

        public data class Submenu(
            public val label: CharSequence,
            @get:DrawableRes public val icon: Int = 0,
            public val entries: List<Entry>,
        ) : Entry

        public data object Divider : Entry
    }

    public class Builder {
        public var material: WitLiquidGlassView.Material? = null
        public var tone: WitLiquidGlassView.Tone? = null

        @get:ColorInt
        @setparam:ColorInt
        public var tintColor: Int? = null

        public var quality: WitLiquidGlassQuality? = null
        public var clearDimming: Float? = null
        public var cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS_DP

        @DrawableRes
        public var anchorIconRes: Int = 0
        @get:ColorInt
        @setparam:ColorInt
        public var anchorIconTint: Int? = null
        public var borderless: Boolean = false
        public var transparentBackground: Boolean = false
        public var dimBackground: Boolean = false
        public var scrim: Boolean
            get() = dimBackground
            set(value) { dimBackground = value }
        @get:ColorInt
        @setparam:ColorInt
        public var startPlatterColor: Int? = null
        public var palette: V3Palette? = null
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

        public fun submenu(
            label: CharSequence,
            @DrawableRes icon: Int = 0,
            block: Builder.() -> Unit,
        ) {
            val subBuilder = Builder().apply(block)
            val subEntries = subBuilder.buildEntries()
            if (subEntries.isNotEmpty()) {
                entries += Entry.Submenu(label, icon, subEntries)
            }
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
            quality = quality,
            tintColor = tintColor,
            clearDimming = clearDimming,
            cornerRadiusDp = cornerRadiusDp,
            anchorIconRes = anchorIconRes,
            anchorIconTint = anchorIconTint,
            borderless = borderless,
            transparentBackground = transparentBackground,
            startPlatterColor = startPlatterColor,
            palette = palette,
            onDismiss = onDismiss,
            dimBackground = dimBackground,
        )
    }

    internal data class Config(
        val material: WitLiquidGlassView.Material?,
        val tone: WitLiquidGlassView.Tone?,
        val quality: WitLiquidGlassQuality?,
        @get:ColorInt val tintColor: Int?,
        val clearDimming: Float?,
        val cornerRadiusDp: Float,
        @get:DrawableRes val anchorIconRes: Int,
        @get:ColorInt val anchorIconTint: Int?,
        val borderless: Boolean,
        val transparentBackground: Boolean,
        @get:ColorInt val startPlatterColor: Int?,
        val palette: V3Palette?,
        val onDismiss: (() -> Unit)?,
        val dimBackground: Boolean = false,
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
    private val palette: V3Palette = config.palette
        ?: V3Palette.from(findActivity(anchor.context) ?: findActivity(host.context) ?: host.context)
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
    private var activeSubmenuGlass: WitLiquidGlassView? = null
    private var activeSubmenuSourceChevron: View? = null
    private var activeSubmenuSourceRow: View? = null
    private var parentRecedeOverlay: View? = null
    private val activeSubmenuToggles = mutableListOf<Pair<SwitchCompat, Entry.Toggle>>()
    private val closingSubmenuViews = mutableListOf<View>()
    private var backGestureOnSubmenu = false
    private var safeLeftPx: Int = 0
    private var safeRightPx: Int = 0
    private var safeTopPx: Int = 0
    private var safeBottomPx: Int = 0

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
    private var lastEvaluatedClosing = false
    private var lastMorphFrame: MorphFrame? = null
    private var closeInterruptStart: MorphFrame? = null
    private var closeStartProgress = 0f
    private var contentLensShader: RuntimeShader? = null
    private var lastContentEffectKey = Long.MIN_VALUE
    private var anchorHidden = false
    private var isBorderlessAnchor = false
    private var isTransparentBackground = false
    private var isDark = false
    private var lastContentBlurPx = -1f
    private var lastEchoBlurPx = -1f
    private var resolvedQuality: WitLiquidGlassQuality = WitLiquidGlassQuality.NONE
    private val isNoneQuality: Boolean
        get() = resolvedQuality == WitLiquidGlassQuality.NONE
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

    @ColorInt
    private var startTint = Color.TRANSPARENT

    @ColorInt
    private var targetTint = Color.TRANSPARENT

    @ColorInt
    private var startPlatterColor = Color.TRANSPARENT

    @ColorInt
    private var derivedPlatterColor = Color.TRANSPARENT

    @ColorInt
    private var targetPlatterColor = Color.TRANSPARENT

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
        closeSubmenu(animated = false)
        flushClosingSubmenuViews()
        cancelDragSprings()
        clearHighlight(animated = false)
        backCallback?.isEnabled = false
        scrimView?.isClickable = false
        setRowsInteractive(false)

        if (!animated || !motionEnabled() || progress <= 0.001f || menuWidth <= 0 || menuHeight <= 0) {
            finishAndCleanup()
            return
        }
        val wasOpenSettled = openSettled || progress >= 0.99f
        val startCloseP = when {
            wasOpenSettled -> {
                closeInterruptStart = null
                0f
            }
            lastEvaluatedClosing -> {
                // Continuing seamlessly from a predictive-back preview along the close curve.
                closeInterruptStart = null
                (1f - progress).coerceIn(0f, 0.95f)
            }
            else -> {
                closeInterruptStart = lastMorphFrame
                // Map current mid-open progress smoothly onto the matching close phase so
                // interrupting early never jumps back to 100% size before retracting.
                val sizeRatio = (progress / 0.42f).coerceIn(0f, 1f)
                ((1f - sizeRatio) * 0.56f).coerceIn(0f, 0.56f)
            }
        }
        closeStartProgress = startCloseP
        val startP = (1f - startCloseP).coerceIn(0.01f, 1f)
        progress = startP
        applyProgress(startP, closing = true)
        val duration = (CLOSE_DURATION_MS * (1f - startCloseP).coerceIn(0.32f, 1f)).toLong()
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
        // 实心顶栏底色替换/透明底模式均由调用方显式声明；未声明时默认为 false，走标准毛玻璃浮动菜单
        isTransparentBackground = config.transparentBackground || (config.startPlatterColor == Color.TRANSPARENT)
        isBorderlessAnchor = config.borderless || isTransparentBackground
        val resolvedTone = config.tone ?: anchorGlass?.tone ?: WitLiquidGlassView.Tone.THEME
        val resolvedQuality = config.quality ?: anchorGlass?.quality ?: host.quality
        this.resolvedQuality = resolvedQuality
        val resolvedMaterial = resolveMenuMaterial(config.material, anchorGlass?.material)
        isDark = when (resolvedTone) {
            WitLiquidGlassView.Tone.THEME -> palette.isDark
            WitLiquidGlassView.Tone.LIGHT -> false
            WitLiquidGlassView.Tone.DARK -> true
        }
        val resolvedClearDimming = resolveMenuClearDimming(
            explicitDimming = config.clearDimming,
            anchorDimming = anchorGlass?.clearDimming,
            resolvedMaterial = resolvedMaterial,
            isDark = isDark,
        )

        startTint = when {
            isTransparentBackground -> Color.TRANSPARENT
            anchorGlass?.tintColor != null -> anchorGlass.tintColor
            else -> Color.TRANSPARENT
        }
        targetTint = when {
            config.tintColor != null -> config.tintColor
            startTint != Color.TRANSPARENT -> startTint
            resolvedMaterial == WitLiquidGlassView.Material.CLEAR -> Color.TRANSPARENT
            isDark -> DEFAULT_DARK_PANEL_TINT
            else -> DEFAULT_LIGHT_PANEL_TINT
        }

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
        safeLeftPx = safeLeft
        safeRightPx = safeRight
        safeTopPx = safeTop
        safeBottomPx = safeBottom
        val availableW = (safeRight - safeLeft).coerceAtLeast(context.dp(160))
        val availableH = (safeBottom - safeTop).coerceAtLeast(context.dp(120))

        // 2) Build menu glass surface and rows.
        val normalCardFill = if (isDark == palette.isDark) palette.cardFill else V3Palette(palette.primary, isDark).cardFill
        targetPlatterColor = normalCardFill
        startPlatterColor = when {
            isTransparentBackground -> Color.TRANSPARENT
            config.startPlatterColor != null -> config.startPlatterColor
            isBorderlessAnchor -> palette.primary
            else -> normalCardFill
        }
        // 迅速形成主题派生色在当前模式下的加权微调视觉差
        derivedPlatterColor = computeWeightedDerivedStartColor(palette, isDark)

        val glass = WitLiquidGlassView(context).apply {
            isLargeSurface = true
            material = resolvedMaterial
            tone = resolvedTone
            quality = resolvedQuality
            clearDimming = resolvedClearDimming
            cornerRadiusDp = config.cornerRadiusDp
            tintColor = Color.TRANSPARENT
            surfaceFillColor = if (isBorderlessAnchor && !isTransparentBackground) startPlatterColor else null
            isGlassInteractive = false
            isClickable = true
            isFocusable = false
            // Keep child content unclipped at laid-out bounds during gravity dip;
            // WitLiquidGlassView.dispatchDraw clips children to the exact live teardrop silhouette.
            clipChildren = false
            clipToPadding = false
            elevation = if (isBorderlessAnchor) 0f else context.dpF(MENU_ELEVATION_DP)
            platterAlpha = if (isBorderlessAnchor) 0f else 1f
            rimAlpha = if (isBorderlessAnchor) 0f else 1f
        }
        val contentColor = glass.contentColor
        val fallbackIconTint = when {
            isTransparentBackground -> contentColor
            isBorderlessAnchor && ColorUtils.calculateLuminance(startPlatterColor) < 0.5 -> Color.WHITE
            else -> contentColor
        }
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
                is Entry.Submenu -> {
                    val row = buildSubmenuRow(entry, contentColor, isDark)
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
                Entry.Divider -> {
                    val divider = createDividerView(contentColor)
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
        anchorLocalR = anchorLocalL + anchorWidthPx
        anchorLocalT = (anchorT - menuTop).toFloat()
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

        // 6) Mirror anchor icon inside the anchor orb for a seamless handoff (directly morphs icon <-> droplet).
        val echo = createAnchorEchoView(anchor, config.anchorIconRes, fallbackIconTint)
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

        // Pre-evaluate frame 0 so the glass surface and echo icon are 1:1 identical to the anchor before being drawn.
        applyProgress(0f, closing = false)

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
        if (host.backdropSiblingParent != null) {
            host.invalidateBackdrop()
        }
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

    private fun buildSubmenuRow(
        entry: Entry.Submenu,
        @ColorInt contentColor: Int,
        isDark: Boolean,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
            setPadding(context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP), context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP))
            background = buildRowRipple(isDark)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (state == State.DISMISSING || state == State.FINISHED || activeSubmenuGlass != null) return@setOnClickListener
                openSubmenu(entry, this)
            }
        }
        if (entry.icon != 0) {
            val iconView = ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(contentColor, 232))
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
            ),
        )
        val chevronView = ImageView(context).apply {
            setImageResource(R.drawable.wit_ic_chevron_right)
            imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(contentColor, 150))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(
            chevronView,
            LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply {
                marginStart = context.dp(8)
            },
        )
        return row
    }

    private fun createDividerView(@ColorInt contentColor: Int): View {
        return object : View(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                    MeasureSpec.getSize(widthMeasureSpec)
                } else {
                    0
                }
                setMeasuredDimension(width, context.hairlinePx())
            }
        }.apply {
            setBackgroundColor(V3Palette.withAlpha(contentColor, 0.14f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun openSubmenu(entry: Entry.Submenu, sourceRow: View) {
        if (state != State.OPENING_OR_OPEN) return
        val parentGlass = menuGlass ?: return
        if (activeSubmenuGlass != null) return

        flushClosingSubmenuViews()
        settleParentForSubmenu()
        parentGlass.animate().cancel()
        sourceRow.animate().cancel()
        clearHighlight(animated = true)
        sourceRow.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

        val rowLoc = IntArray(2)
        val hostLoc = IntArray(2)
        sourceRow.getLocationInWindow(rowLoc)
        host.getLocationInWindow(hostLoc)
        val rowLeftInHost = rowLoc[0] - hostLoc[0]
        val rowTopInHost = rowLoc[1] - hostLoc[1]
        val rowW = sourceRow.width
        val rowH = sourceRow.height

        val sourceChevron = (sourceRow as? ViewGroup)?.let { vg ->
            (0 until vg.childCount).map { vg.getChildAt(it) }.filterIsInstance<ImageView>().lastOrNull()
        }
        activeSubmenuSourceRow = sourceRow
        activeSubmenuSourceChevron = sourceChevron
        sourceChevron?.animate()?.cancel()
        sourceChevron?.animate()?.rotation(90f)?.setDuration(220L)?.start()

        val subRowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padV = context.dp(CONTAINER_PAD_V_DP)
            setPadding(0, padV, 0, padV)
        }

        val contentColor = parentGlass.contentColor
        val dangerColor = if (isDark) DARK_DANGER_COLOR else LIGHT_DANGER_COLOR

        // Header / Collapse bar: 文字稳定左对齐，右侧向下折叠箭头指示展开态
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
            setPadding(context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP), context.dp(ROW_PAD_H_DP), context.dp(ROW_PAD_V_DP))
            background = buildRowRipple(isDark)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                closeSubmenu(animated = true)
            }
        }
        if (entry.icon != 0) {
            val iconView = ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(contentColor, 232))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            headerRow.addView(
                iconView,
                LinearLayout.LayoutParams(context.dp(ICON_SIZE_DP), context.dp(ICON_SIZE_DP)).apply {
                    marginEnd = context.dp(ICON_GAP_DP)
                },
            )
        }
        val headerTitle = context.label(entry.label, size = 15.5f, weight = 500, color = contentColor).apply {
            lineHeightRatio(1.3f)
        }
        headerRow.addView(
            headerTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        val chevronView = ImageView(context).apply {
            setImageResource(R.drawable.wit_ic_chevron_right)
            imageTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(contentColor, 180))
            rotation = 90f // 顺时针旋转 90 度为向下展开状态
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        headerRow.addView(
            chevronView,
            LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply {
                marginStart = context.dp(8)
            },
        )
        subRowsContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                val hMargin = context.dp(ROW_HORIZONTAL_MARGIN_DP)
                marginStart = hMargin
                marginEnd = hMargin
            },
        )

        // Divider below header
        val headerDivider = createDividerView(contentColor)
        subRowsContainer.addView(
            headerDivider,
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

        // Sub items
        entry.entries.forEach { subEntry ->
            when (subEntry) {
                is Entry.Action -> {
                    val row = buildActionRow(subEntry, contentColor, dangerColor, isDark)
                    subRowsContainer.addView(
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
                    val (row, toggle) = buildToggleRow(subEntry, contentColor, isDark)
                    activeSubmenuToggles += toggle to subEntry
                    subRowsContainer.addView(
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
                is Entry.Submenu -> {
                    val row = buildSubmenuRow(subEntry, contentColor, isDark)
                    subRowsContainer.addView(
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
                    val divider = createDividerView(contentColor)
                    subRowsContainer.addView(
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

        // 对齐 chen08209/prism_ui 的 _levelWidthScale = 1.15：子菜单宽度扩展 16%，向左探出形成立体阶梯层次
        val maxAvailableW = (safeRightPx - safeLeftPx).coerceAtLeast(context.dp(160))
        val subW = minOf((menuWidth * 1.16f).roundToInt(), maxAvailableW)
        subRowsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(subW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val availableH = (safeBottomPx - safeTopPx).coerceAtLeast(context.dp(120))
        val maxSubH = minOf((availableH * MAX_HEIGHT_FRACTION).toInt(), context.dp(MAX_MENU_HEIGHT_DP))
            .coerceAtMost(availableH)
            .coerceAtLeast(context.dp(96))
        val subH = subRowsContainer.measuredHeight.coerceAtMost(maxSubH).coerceAtLeast(context.dp(56))

        // 右边缘对齐（Alignment.topRight）：以父菜单原始右边界对齐，向左侧舒展探出 16%，形成卡片堆叠的纵深阶梯感
        val parentLeft = (parentGlass.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: parentGlass.left
        val parentRight = parentLeft + menuWidth
        val rawSubLeft = parentRight - subW
        val subLeft = rawSubLeft.coerceIn(safeLeftPx, (safeRightPx - subW).coerceAtLeast(safeLeftPx))
        val idealTop = rowTopInHost
        val subTop = idealTop.coerceIn(safeTopPx, (safeBottomPx - subH).coerceAtLeast(safeTopPx))

        // 子菜单完全继承父菜单的原生毛玻璃底（对齐 prism_ui 的 LiquidGlassStyle.menu 统一样式）：
        // 开启 isLargeSurface 支持面板级动态降采样与柔和光晕，保持与主菜单同质、通透、晶莹的 LiquidGlass 玻璃底
        val subGlass = WitLiquidGlassView(context).apply {
            isLargeSurface = true
            material = parentGlass.material
            tone = parentGlass.tone
            quality = parentGlass.quality
            clearDimming = parentGlass.clearDimming
            cornerRadiusDp = parentGlass.cornerRadiusDp
            tintColor = parentGlass.tintColor
            surfaceFillColor = parentGlass.surfaceFillColor
            isGlassInteractive = false
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            elevation = context.dpF(MENU_ELEVATION_DP + 16f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = if (isDark) 0x66000000.toInt() else 0x24000000
                outlineSpotShadowColor = if (isDark) 0x88000000.toInt() else 0x3D000000
            }
            platterAlpha = 1f
            rimAlpha = 1f
        }

        val scrollContent = FrameLayout(context).apply {
            addView(
                subRowsContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val scroller = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val subCornerRadiusPx = minOf(context.dpF(config.cornerRadiusDp), minOf(subW, subH) * 0.5f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, subCornerRadiusPx)
                }
            }
            clipToOutline = true
            addView(
                scrollContent,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        subGlass.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // 父菜单拦截与退避弱化层（对齐 chen08209/prism_ui 的 dim: colorScheme.surface.withValues(alpha: 0.28)）：
        // 父菜单卡片整体不透明度保持 1.0f，露出的上下选项正常可见，仅覆盖微弱蒙层弱化焦点；
        // 同时拦截退避父菜单的任意点击，用于收回子菜单。
        val surfaceColor = if (isDark) Color.BLACK else Color.WHITE
        val dimColor = ColorUtils.setAlphaComponent(
            surfaceColor,
            (255 * 0.28f).roundToInt(),
        )
        val recedeOverlay = View(context).apply {
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(dimColor)
            alpha = 0f
            setOnClickListener {
                closeSubmenu(animated = true)
            }
        }
        parentGlass.addView(
            recedeOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        parentRecedeOverlay = recedeOverlay

        // 仅隐藏父菜单中被展开的那一行（对齐 prism_ui 的 covered 机制：opacity: index == covered ? 0 : 1），避免重影
        sourceRow.alpha = 0f

        subGlass.pivotX = ((rowLeftInHost + rowW * 0.5f) - subLeft).coerceIn(0f, subW.toFloat())
        subGlass.pivotY = ((rowTopInHost + rowH * 0.5f) - subTop).coerceIn(0f, subH.toFloat())
        subGlass.scaleX = 0.88f
        subGlass.scaleY = 0.88f
        subGlass.alpha = 0f

        host.addView(
            subGlass,
            FrameLayout.LayoutParams(subW, subH, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = subLeft
                topMargin = subTop
            },
        )
        activeSubmenuGlass = subGlass

        val rowCenterYInParent = (rowTopInHost - parentGlass.top + rowH * 0.5f).coerceIn(0f, parentGlass.height.toFloat())
        val targetPivotX = if (anchorOnRight) parentGlass.width.toFloat() else 0f

        if (motionEnabled()) {
            subGlass.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()

            parentGlass.pivotX = targetPivotX
            parentGlass.pivotY = rowCenterYInParent
            parentGlass.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()

            recedeOverlay.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        } else {
            subGlass.scaleX = 1f
            subGlass.scaleY = 1f
            subGlass.alpha = 1f
            parentGlass.pivotX = targetPivotX
            parentGlass.pivotY = rowCenterYInParent
            parentGlass.scaleX = 0.95f
            parentGlass.scaleY = 0.95f
            recedeOverlay.alpha = 1f
        }
    }

    private fun closeSubmenu(animated: Boolean = true) {
        backGestureOnSubmenu = false
        if (activeSubmenuToggles.isNotEmpty()) {
            toggles.removeAll(activeSubmenuToggles)
            activeSubmenuToggles.clear()
        }
        val subGlass = activeSubmenuGlass ?: return
        val parentGlass = menuGlass
        val recedeOverlay = parentRecedeOverlay
        val sourceChevron = activeSubmenuSourceChevron
        val sourceRow = activeSubmenuSourceRow
        activeSubmenuGlass = null
        parentRecedeOverlay = null
        activeSubmenuSourceChevron = null
        activeSubmenuSourceRow = null

        if (animated && motionEnabled()) {
            sourceChevron?.animate()?.cancel()
            sourceChevron?.animate()?.rotation(0f)?.setDuration(200L)?.start()
            sourceRow?.let {
                it.animate().cancel()
                it.animate().alpha(1f).setDuration(180L).start()
            }

            closingSubmenuViews += subGlass
            subGlass.animate().cancel()
            subGlass.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .alpha(0f)
                .setDuration(180L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    closingSubmenuViews.remove(subGlass)
                    (subGlass.parent as? ViewGroup)?.removeView(subGlass)
                }
                .start()

            recedeOverlay?.let { overlay ->
                closingSubmenuViews += overlay
                overlay.animate().cancel()
                overlay.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        closingSubmenuViews.remove(overlay)
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                    }
                    .start()
            }

            parentGlass?.animate()?.cancel()
            parentGlass?.animate()
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(200L)
                ?.setInterpolator(DecelerateInterpolator(1.8f))
                ?.start()
        } else {
            sourceChevron?.animate()?.cancel()
            sourceChevron?.rotation = 0f
            sourceRow?.animate()?.cancel()
            sourceRow?.alpha = 1f
            subGlass.animate().cancel()
            closingSubmenuViews.remove(subGlass)
            (subGlass.parent as? ViewGroup)?.removeView(subGlass)
            recedeOverlay?.let {
                it.animate().cancel()
                closingSubmenuViews.remove(it)
                (it.parent as? ViewGroup)?.removeView(it)
            }
            parentGlass?.let {
                it.animate().cancel()
                it.scaleX = 1f
                it.scaleY = 1f
            }
        }
    }

    private fun flushClosingSubmenuViews() {
        if (closingSubmenuViews.isEmpty()) return
        val pending = closingSubmenuViews.toList()
        closingSubmenuViews.clear()
        pending.forEach { view ->
            view.animate().cancel()
            (view.parent as? ViewGroup)?.removeView(view)
        }
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
        val anchorIv = findAnchorImageView(anchor)
        val extracted = if (overrideIconRes != 0) null else extractAnchorDrawable(anchor)
        val drawable = if (overrideIconRes != 0) {
            androidx.core.content.ContextCompat.getDrawable(context, overrideIconRes)
        } else {
            extracted?.first ?: androidx.core.content.ContextCompat.getDrawable(context, R.drawable.wit_ic_more_vert)
        } ?: return null

        val tintList = config.anchorIconTint?.let { ColorStateList.valueOf(it) }
            ?: extracted?.second
            ?: anchorIv?.imageTintList
            ?: (anchor as? ImageView)?.imageTintList
            ?: ColorStateList.valueOf(fallbackTint)

        return ImageView(context).apply {
            setImageDrawable(drawable)
            imageTintList = tintList
            // 严格以 1:1 原寸（24dp）居中呈现，绝不在 48~56dp 等大尺寸顶栏按钮中缩放放大
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun extractAnchorDrawable(view: View): Pair<Drawable, ColorStateList?>? {
        // 1. 若为 ActionMenuItemView，直接获取内部关联的 MenuItemImpl 图标
        try {
            val method = view.javaClass.getMethod("getItemData")
            val itemData = method.invoke(view)
            if (itemData != null) {
                val iconMethod = itemData.javaClass.getMethod("getIcon")
                val icon = iconMethod.invoke(itemData) as? Drawable
                if (icon != null) {
                    val cloned = icon.constantState?.newDrawable(view.resources)?.mutate() ?: icon
                    return cloned to (view as? ImageView)?.imageTintList
                }
            }
        } catch (_: Throwable) {}

        if (view is ImageView) {
            val d = view.drawable
            if (d != null) {
                val cloned = d.constantState?.newDrawable(view.resources)?.mutate() ?: d
                return cloned to view.imageTintList
            }
        }
        if (view is android.widget.TextView) {
            val drawables = if (Build.VERSION.SDK_INT >= 17) {
                view.compoundDrawablesRelative
            } else {
                view.compoundDrawables
            }
            for (d in drawables) {
                if (d != null) {
                    val cloned = d.constantState?.newDrawable(view.resources)?.mutate() ?: d
                    val tint = if (Build.VERSION.SDK_INT >= 23) view.compoundDrawableTintList else null
                    return cloned to (tint ?: ColorStateList.valueOf(Color.WHITE))
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = extractAnchorDrawable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        val entryName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { null }
        if (entryName == "action_more" || entryName == "more") {
            val d = androidx.core.content.ContextCompat.getDrawable(view.context, R.drawable.wit_ic_more_vert)
            if (d != null) {
                return d.mutate() to ColorStateList.valueOf(Color.WHITE)
            }
        }
        return null
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
        if (state == State.FINISHED) return
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
                    if (target <= 0f) {
                        finishAndCleanup()
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || state == State.FINISHED) {
                        if (target <= 0f) {
                            finishAndCleanup()
                        }
                        return
                    }
                    if (target <= 0f) {
                        applyProgress(0f, closing = true)
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

    private fun settleParentForSubmenu() {
        morphAnimator?.cancel()
        morphAnimator = null
        cancelDragSprings()
        dragFingerDown = false
        val needsSettle = !openSettled ||
            abs(dragPullX) > 0.5f ||
            abs(dragPullY) > 0.5f ||
            abs(dragPress) > 0.01f
        if (!needsSettle) return
        applyProgress(1f, closing = false)
        settleOpen(focus = false)
    }

    private fun settleOpen(focus: Boolean = true) {
        openSettled = true
        lastEvaluatedClosing = false
        closeInterruptStart = null
        closeStartProgress = 0f
        dragPullX = 0f
        dragPullY = 0f
        dragPress = 0f
        val w = menuWidth.toFloat()
        val h = menuHeight.toFloat()
        lastMorphFrame = MorphFrame(
            bodyLeft = 0f,
            bodyTop = 0f,
            bodyRight = w,
            bodyBottom = h,
            bodyRadius = endRadiusPx,
            orbX = 0f,
            orbY = 0f,
            orbRadius = 0f,
            orbBlendPx = 0f,
            contentAlpha = 1f,
            contentBlurDp = 0f,
            contentRefract = 0f,
            echoAlpha = 0f,
            echoBlurDp = 0f,
            echoScale = 1f,
            echoCenterX = anchorCenterX,
            echoCenterY = anchorCenterY,
        )
        menuGlass?.let { glass ->
            glass.translationX = 0f
            glass.translationY = 0f
            glass.scaleX = 1f
            glass.scaleY = 1f
            glass.alpha = 1f
            if (isBorderlessAnchor && !isTransparentBackground) {
                glass.surfaceFillColor = targetPlatterColor
            } else {
                glass.surfaceFillColor = null
            }
            glass.platterAlpha = 1f
            glass.rimAlpha = 1f
            glass.elevation = context.dpF(MENU_ELEVATION_DP)
            glass.setMorphFrame(0f, 0f, w, h, endRadiusPx)
            glass.setMorphTouch(0f, 0f, 0f)
        }
        scrollView?.let { content ->
            content.alpha = 1f
            content.scaleX = 1f
            content.scaleY = 1f
            content.translationX = 0f
            content.translationY = 0f
            clearContentEffect(content)
        }
        echoIconView?.let { echo ->
            echo.alpha = 0f
            echo.scaleX = 1f
            echo.scaleY = 1f
            echo.translationX = 0f
            echo.translationY = 0f
            setViewBlur(echo, 0f, isContent = false)
        }
        if (focus) focusFirstRow()
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
            private var lastDownEvent: MotionEvent? = null
            private val scrubActivateRunnable = Runnable {
                if (dragMode == DragMode.UNDECIDED && highlightedRow != null) {
                    dragMode = DragMode.SCRUB
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    lastDownEvent?.let { sendCancelToSuper(it) }
                }
            }

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
                            if (canScroll) {
                                lastDownEvent?.recycle()
                                lastDownEvent = MotionEvent.obtain(ev)
                                removeCallbacks(scrubActivateRunnable)
                                postDelayed(scrubActivateRunnable, 200L)
                            }
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
                            removeCallbacks(scrubActivateRunnable)
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
                                if (canScroll) {
                                    val edgePad = context.dpF(36f)
                                    if (ly < edgePad && scrollY > 0) {
                                        val speed = ((edgePad - ly) / edgePad).coerceIn(0.2f, 1f)
                                        val step = (context.dpF(6f) * speed).toInt().coerceAtLeast(1)
                                        scrollBy(0, -step)
                                    } else if (ly > height - edgePad && scrollY < maxScroll) {
                                        val speed = ((ly - (height - edgePad)) / edgePad).coerceIn(0.2f, 1f)
                                        val step = (context.dpF(6f) * speed).toInt().coerceAtLeast(1)
                                        scrollBy(0, step)
                                    }
                                }
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
                        removeCallbacks(scrubActivateRunnable)
                        lastDownEvent?.recycle()
                        lastDownEvent = null
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
                        removeCallbacks(scrubActivateRunnable)
                        lastDownEvent?.recycle()
                        lastDownEvent = null
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
        animateDragSpring(targetX = 0f, targetY = 0f, targetPress = 1f, releasing = false)
    }

    private fun updateDragTargets(x: Float, y: Float, pullX: Float, pullY: Float) {
        dragTouchX = x
        dragTouchY = y
        if (!openSettled || !motionEnabled() || isNoneQuality) return
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
                is Entry.Submenu -> {
                    clearHighlight(animated = false)
                    openSubmenu(entry, commitRow.view)
                    return
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
        val w = menuWidth.toFloat()
        val h = menuHeight.toFloat()
        menuGlass?.let { glass ->
            glass.translationX = 0f
            glass.translationY = 0f
            glass.setMorphFrame(0f, 0f, w, h, endRadiusPx)
            glass.setMorphTouch(0f, 0f, 0f)
        }
        scrollView?.let { content ->
            content.scaleX = 1f
            content.scaleY = 1f
            content.translationX = 0f
            content.translationY = 0f
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
     * Evaluates the 1:1 iOS 26 calibrated Liquid Glass morph at normalized progress [rawP] in `0..1`:
     *
     * Open (`closing = false`, `520ms`):
     * - `0..75ms`: anchor orb launches diagonally down-left as a liquid teardrop (`smin` trailing
     *   shoulder + right-edge inward pull `-13dp`); anchor dots drift with the droplet and dissolve
     *   in `~45ms`.
     * - `75..250ms`: spring-driven inflation (`omega ≈ 18.2, zeta ≈ 0.71`) with height-invariant
     *   downward gravity momentum (`+46dp` peak top sag at `100ms`, `+17dp` peak bottom overshoot
     *   at `184ms`); menu content fades in (`70..215ms`), clears Gaussian blur (`16dp -> 0dp`), and
     *   subtly refracts around the curved bubble bevel (API 33+).
     * - `250..520ms`: width/height overshoot (`~+2.9%`) and corner relaxation (`capsule -> 32dp`
     *   by `330ms`) settle smoothly with a delicate `-2dp` top recoil.
     *
     * Close (`closing = true`, `500ms`, `closeP = 1 - p`):
     * - `0..67ms`: downward release dip (`+48dp` top drop, `+18dp` bottom dip) while menu content
     *   blurs and dissolves completely to `0%`, leaving pure empty liquid glass.
     * - `118..305ms`: anchor orb buds at the upper-right shoulder and fuses with the shrinking
     *   droplet belly via an `smin` concave metaball bridge (spout -> gourd -> peanut), while the
     *   anchor dots emerge inside the orb and ride it past rest (`-7dp` upward recoil).
     * - `305..500ms`: belly is fully absorbed into the circular orb, which elastically bounces back
     *   to exact anchor rest with zero handoff pop.
     */
    private fun applyProgress(rawP: Float, closing: Boolean) {
        val glass = menuGlass ?: return
        val p = rawP.coerceIn(0f, 1f)
        progress = p
        lastEvaluatedClosing = closing

        val w = menuWidth.toFloat()
        val h = menuHeight.toFloat()
        val density = context.resources.displayMetrics.density
        val patchMargin = context.dpF(MAX_MORPH_PATCH_OVERSHOOT_DP)

        val baseFrame = if (!closing) {
            evaluateOpenMorph(
                progress = p,
                menuW = w,
                menuH = h,
                anchorL = anchorLocalL,
                anchorT = anchorLocalT,
                anchorW = anchorWidthPx,
                anchorH = anchorHeightPx,
                endRadiusPx = endRadiusPx,
                density = density,
                anchorOnRight = anchorOnRight,
                expandingDown = expandingDown,
                patchMargin = patchMargin,
            )
        } else {
            val closeP = (1f - p).coerceIn(0f, 1f)
            val targetFrame = evaluateCloseMorph(
                closeProgress = closeP,
                menuW = w,
                menuH = h,
                anchorL = anchorLocalL,
                anchorT = anchorLocalT,
                anchorW = anchorWidthPx,
                anchorH = anchorHeightPx,
                endRadiusPx = endRadiusPx,
                density = density,
                anchorOnRight = anchorOnRight,
                expandingDown = expandingDown,
                patchMargin = patchMargin,
            )
            val startSnap = closeInterruptStart
            if (startSnap != null) {
                val blendSpan = (0.18f * (1f - closeStartProgress)).coerceAtLeast(0.06f)
                val blend = smoothstep(closeStartProgress, (closeStartProgress + blendSpan).coerceAtMost(1f), closeP)
                if (blend < 0.999f) lerpMorphFrame(startSnap, targetFrame, blend) else targetFrame
            } else {
                targetFrame
            }
        }

        // When dismissing straight from an active drag/press, blend the residual elastic offset
        // smoothly to zero over the opening phase of the close morph so there is zero frame snap.
        val dragCarry = if (closing && (abs(dragPullX) > 0.5f || abs(dragPullY) > 0.5f || dragPress > 0.01f)) {
            smoothstep(0.82f, 1f, p)
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
                density = density,
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
            val mergedW = (baseFrame.bodyRight - baseFrame.bodyLeft + dR - dL).coerceAtLeast(0f)
            val mergedH = (baseFrame.bodyBottom - baseFrame.bodyTop + dB - dT).coerceAtLeast(0f)
            finalL = clampMorphStart(baseFrame.bodyLeft + dL, mergedW, w, patchMargin)
            finalR = finalL + mergedW
            finalT = clampMorphStart(baseFrame.bodyTop + dT, mergedH, h, patchMargin)
            finalB = finalT + mergedH
        } else {
            glass.translationX = 0f
            glass.translationY = 0f
            finalL = baseFrame.bodyLeft
            finalT = baseFrame.bodyTop
            finalR = baseFrame.bodyRight
            finalB = baseFrame.bodyBottom
        }

        val finalW = (finalR - finalL).coerceAtLeast(0f)
        val finalH = (finalB - finalT).coerceAtLeast(0f)
        val bodyR = baseFrame.bodyRadius.coerceIn(0f, minOf(finalW, finalH) * 0.5f)
        val bodyCx = (finalL + finalR) * 0.5f
        val bodyCy = (finalT + finalB) * 0.5f

        lastMorphFrame = if (dragCarry > 0.001f) {
            baseFrame.copy(bodyLeft = finalL, bodyTop = finalT, bodyRight = finalR, bodyBottom = finalB, bodyRadius = bodyR)
        } else {
            baseFrame
        }

        if (baseFrame.orbRadius > 0.5f && baseFrame.orbBlendPx > 0.01f) {
            glass.setMorphFrame(
                left = finalL,
                top = finalT,
                right = finalR,
                bottom = finalB,
                cornerRadiusPx = bodyR,
                orbX = baseFrame.orbX,
                orbY = baseFrame.orbY,
                orbRadius = baseFrame.orbRadius,
                orbBlendPx = baseFrame.orbBlendPx,
            )
        } else {
            glass.setMorphFrame(finalL, finalT, finalR, finalB, bodyR)
        }
        glass.setMorphTouch(
            x = (dragTouchX - glass.translationX).coerceIn(-w * 0.25f, w * 1.25f),
            y = (dragTouchY - glass.translationY).coerceIn(-h * 0.25f, h * 1.25f),
            illumination = dragPress.coerceIn(0f, 1f) * dragCarry * DRAG_TOUCH_GLOW_STRENGTH,
        )

        glass.alpha = 1f

        if (isBorderlessAnchor) {
            if (isTransparentBackground) {
                // 透明底（详情页 3 个点）：保持纯正毛玻璃材质，底色从透明凝聚水滴，随加权派生色自然涌出
                glass.surfaceFillColor = null
                val derivedTint = ColorUtils.setAlphaComponent(
                    derivedPlatterColor,
                    (Color.alpha(targetTint) * (if (isDark) 0.85f else 0.70f)).roundToInt().coerceIn(0, 255),
                )
                glass.tintColor = computeSurfaceFill(
                    progress = p,
                    startColor = Color.TRANSPARENT,
                    derivedColor = derivedTint,
                    targetColor = targetTint,
                    isBorderless = true,
                    closing = closing,
                )
                glass.platterAlpha = computeAnimAlpha(p, closing = closing, isBorderless = true)
                glass.rimAlpha = computeRimAlpha(p, true)
                glass.elevation = context.dpF(MENU_ELEVATION_DP) * computeElevationFraction(p, true)
            } else {
                val computedSurface = computeSurfaceFill(
                    progress = p,
                    startColor = startPlatterColor,
                    derivedColor = derivedPlatterColor,
                    targetColor = targetPlatterColor,
                    isBorderless = true,
                    closing = closing,
                )
                glass.tintColor = if (!isNoneQuality && targetTint != Color.TRANSPARENT) {
                    ColorUtils.setAlphaComponent(computedSurface, Color.alpha(targetTint))
                } else {
                    Color.TRANSPARENT
                }
                glass.surfaceFillColor = computedSurface
                glass.platterAlpha = 1f
                glass.rimAlpha = computeRimAlpha(p, true)
                glass.elevation = context.dpF(MENU_ELEVATION_DP) * computeElevationFraction(p, true)
            }
        } else {
            // 标准/已有玻璃锚点浮动菜单模式
            glass.surfaceFillColor = null
            glass.tintColor = ColorUtils.blendARGB(startTint, targetTint, smoothstep(0f, 0.25f, p))
            glass.platterAlpha = 1f
            glass.rimAlpha = 1f
            glass.elevation = context.dpF(MENU_ELEVATION_DP)
        }

        // Menu content tracks Body B's live center/size, clears Gaussian blur, and gently refracts
        // near the curved liquid bubble bevel during inflation/release (API 33+).
        scrollView?.let { content ->
            val minScale = if (closing) 0.42f else 0.48f
            val sx = (finalW / w).coerceIn(minScale, 1.08f)
            val sy = (finalH / h).coerceIn(minScale, 1.08f)
            val uni = minOf(sx, sy)
            val appliedSx = lerp(uni, sx, 0.38f)
            val appliedSy = lerp(uni, sy, 0.38f)
            content.scaleX = appliedSx
            content.scaleY = appliedSy
            content.translationX = bodyCx - w * 0.5f
            content.translationY = bodyCy - h * 0.5f
            content.alpha = baseFrame.contentAlpha

            val useOptics = (isTransparentBackground || !isBorderlessAnchor) && !isNoneQuality &&
                baseFrame.contentAlpha > 0.005f
            if (useOptics && Build.VERSION.SDK_INT >= 31) {
                val blurPx = baseFrame.contentBlurDp * density
                val localR = (bodyR / minOf(appliedSx, appliedSy).coerceAtLeast(0.4f))
                    .coerceIn(0f, minOf(w, h) * 0.5f)
                val refract = if (resolvedQuality == WitLiquidGlassQuality.ULTRA) baseFrame.contentRefract else 0f
                applyContentEffect(content, blurPx, refract, w, h, localR, density)
            } else {
                clearContentEffect(content)
            }
        }

        echoIconView?.let { echo ->
            echo.translationX = baseFrame.echoCenterX - anchorCenterX
            echo.translationY = baseFrame.echoCenterY - anchorCenterY
            if (isBorderlessAnchor && !isTransparentBackground) {
                // 实心顶栏按钮：保持 1:1 原寸不缩放畸变，变色期清晰停留拉开视觉差，拉伸时自然淡出
                echo.scaleX = 1f
                echo.scaleY = 1f
                echo.alpha = if (!closing) {
                    1f - smoothstep(0.08f, 0.24f, p)
                } else {
                    baseFrame.echoAlpha
                }
                setViewBlur(echo, 0f, isContent = false)
            } else {
                echo.alpha = baseFrame.echoAlpha
                echo.scaleX = baseFrame.echoScale
                echo.scaleY = baseFrame.echoScale
                if (!isNoneQuality && Build.VERSION.SDK_INT >= 31 && baseFrame.echoAlpha > 0.005f) {
                    setViewBlur(echo, baseFrame.echoBlurDp * density, isContent = false)
                } else {
                    setViewBlur(echo, 0f, isContent = false)
                }
            }
        }

        scrimDrawable?.alpha = computeScrimAlpha(p, isDark, config.dimBackground)
    }

    private fun clearContentEffect(view: View) {
        if (Build.VERSION.SDK_INT < 31) return
        if (lastContentBlurPx == 0f && lastContentEffectKey == 0L) return
        lastContentBlurPx = 0f
        lastContentEffectKey = 0L
        view.setRenderEffect(null)
    }

    private fun applyContentEffect(
        view: View,
        blurPx: Float,
        refractStrength: Float,
        localW: Float,
        localH: Float,
        localCornerRadiusPx: Float,
        density: Float,
    ) {
        if (Build.VERSION.SDK_INT < 31) return
        val qBlur = if (blurPx < 0.5f) 0f else (blurPx * 2f).roundToInt() * 0.5f
        val qRefract = if (Build.VERSION.SDK_INT >= 33 && refractStrength >= 0.015f) {
            (refractStrength * 50f).roundToInt() / 50f
        } else {
            0f
        }
        val qRad = (localCornerRadiusPx * 0.5f).roundToInt() * 2f
        val key = (qBlur.roundToInt().toLong() shl 32) xor
            (((qRefract * 100f).roundToInt().toLong() and 0xFFFFL) shl 16) xor
            (qRad.roundToInt().toLong() and 0xFFFFL)
        if (key == lastContentEffectKey) return
        lastContentEffectKey = key
        lastContentBlurPx = qBlur

        if (qBlur <= 0f && qRefract <= 0f) {
            view.setRenderEffect(null)
            return
        }
        val blurEffect = if (qBlur > 0f) {
            RenderEffect.createBlurEffect(qBlur, qBlur, Shader.TileMode.CLAMP)
        } else {
            null
        }
        if (qRefract > 0f && Build.VERSION.SDK_INT >= 33 && WitLiquidGlassBackend.REFRACTION.available) {
            val lensEffect = createContentLensEffect(localW, localH, qRad, qRefract, density)
            if (lensEffect != null) {
                view.setRenderEffect(
                    if (blurEffect != null) RenderEffect.createChainEffect(lensEffect, blurEffect) else lensEffect,
                )
                return
            }
        }
        view.setRenderEffect(blurEffect)
    }

    @RequiresApi(33)
    private fun createContentLensEffect(
        localW: Float,
        localH: Float,
        cornerRadiusPx: Float,
        strength: Float,
        density: Float,
    ): RenderEffect? = WitLiquidGlassBackend.REFRACTION.attempt {
        val shader = contentLensShader ?: RuntimeShader(CONTENT_LENS_SHADER_SOURCE).also {
            contentLensShader = it
        }
        val hw = (localW * 0.5f).coerceAtLeast(1f)
        val hh = (localH * 0.5f).coerceAtLeast(1f)
        val bevelPx = (24f * density).coerceAtMost(minOf(hw, hh) * 0.45f)
        shader.setFloatUniform("bounds", hw, hh, hw, hh)
        shader.setFloatUniform("lens", cornerRadiusPx.coerceIn(0f, minOf(hw, hh)), bevelPx, strength)
        RenderEffect.createRuntimeShaderEffect(shader, "content")
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
                if (activeSubmenuGlass != null) {
                    backGestureOnSubmenu = true
                    activeSubmenuGlass?.animate()?.cancel()
                    return
                }
                backGestureOnSubmenu = false
                openSettled = false
                closeInterruptStart = null
                closeStartProgress = 0f
                dragFingerDown = false
                cancelDragSprings()
                clearHighlight(animated = false)
                morphAnimator?.cancel()
                backStartProgress = progress.coerceAtLeast(0.05f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (state != State.OPENING_OR_OPEN) return
                val raw = backEvent.progress.coerceIn(0f, 1f)
                val eased = 1f - (1f - raw) * (1f - raw)
                if (backGestureOnSubmenu) {
                    activeSubmenuGlass?.let { sub ->
                        val s = 1f - eased * 0.08f
                        sub.scaleX = s
                        sub.scaleY = s
                        sub.alpha = (1f - eased * 0.18f).coerceIn(0f, 1f)
                    }
                    return
                }
                applyProgress((backStartProgress * (1f - eased * 0.24f)).coerceAtLeast(0f), closing = true)
            }

            override fun handleOnBackCancelled() {
                if (state != State.OPENING_OR_OPEN) return
                if (backGestureOnSubmenu) {
                    backGestureOnSubmenu = false
                    activeSubmenuGlass?.let { sub ->
                        if (motionEnabled()) {
                            sub.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(160L)
                                .setInterpolator(DecelerateInterpolator(1.6f))
                                .start()
                        } else {
                            sub.scaleX = 1f
                            sub.scaleY = 1f
                            sub.alpha = 1f
                        }
                    }
                    return
                }
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
                backGestureOnSubmenu = false
                if (activeSubmenuGlass != null) {
                    closeSubmenu(animated = true)
                } else {
                    dismiss(animated = true)
                }
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
        closeSubmenu(animated = false)
        flushClosingSubmenuViews()
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

        val glass = menuGlass
        if (glass != null) {
            detachListener?.let { glass.removeOnAttachStateChangeListener(it) }
            glass.visibility = View.GONE
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
        if (host.tag == OVERLAY_HOST_TAG && host.childCount == 0) {
            host.backdropSiblingParent = null
            (host.parent as? ViewGroup)?.removeView(host)
        }
        scrimView = null
        scrimDrawable = null
        scrollView?.let { clearContentEffect(it) }
        scrollView = null
        contentLensShader = null
        lastMorphFrame = null
        closeInterruptStart = null
        closeStartProgress = 0f
        rowsListView = null
        highlightPill = null
        highlightedRow = null
        echoIconView?.let { setViewBlur(it, 0f, isContent = false) }
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
        private const val DEFAULT_CORNER_RADIUS_DP = 32f
        private const val MIN_MENU_WIDTH_DP = 212
        private const val MAX_MENU_WIDTH_DP = 272
        private const val MAX_MENU_HEIGHT_DP = 480
        private const val MAX_HEIGHT_FRACTION = 0.72f
        private const val EDGE_MARGIN_DP = 12
        private const val TOP_MARGIN_DP = 6
        private const val BOTTOM_MARGIN_DP = 16
        private const val TOP_RISE_OVER_ANCHOR_DP = 2
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

        internal const val OPEN_DURATION_MS = 520L
        internal const val CLOSE_DURATION_MS = 500L
        private const val OPEN_DURATION_SEC = OPEN_DURATION_MS / 1000f
        private const val CLOSE_DURATION_SEC = CLOSE_DURATION_MS / 1000f
        /** Strictly inside [WitLiquidGlassBudget.inset] (30dp) minus the 3dp large-surface scatter blur. */
        private const val MAX_MORPH_PATCH_OVERSHOOT_DP = 26f
        private const val CONTENT_OPEN_MAX_BLUR_DP = 16f
        private const val CONTENT_CLOSE_MAX_BLUR_DP = 14f
        private const val ECHO_OPEN_MAX_BLUR_DP = 11f
        private const val ECHO_CLOSE_MAX_BLUR_DP = 8.5f

        private const val CONTENT_LENS_SHADER_SOURCE = """
            uniform shader content;
            uniform float4 bounds; // cx, cy, halfW, halfH in scrollView-local px
            uniform float3 lens;   // cornerRadiusPx, bevelPx, strength (0..1)

            half4 main(float2 coord) {
                float2 centered = coord - bounds.xy;
                float2 halfSize = bounds.zw;
                float radius = min(lens.x, min(halfSize.x, halfSize.y));
                float2 q = abs(centered) - halfSize + radius;
                float2 corner = max(q, 0.0);
                float dist = length(corner) + min(max(q.x, q.y), 0.0) - radius;
                float depth = max(-dist, 0.0);
                float bevel = max(lens.y, 1.0);
                float t = clamp(depth / bevel, 0.0, 1.0);
                float slope = (pow(1.0 + 3.5 * t, -1.35) - 0.1312) / 0.8688;
                float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
                float2 normal = (length(corner) > 0.001 ? normalize(corner) : axis) * sign(centered);
                normal /= max(length(normal), 0.001);
                float2 sampleCoord = coord - normal * (lens.z * bevel * 0.46 * slope);
                return content.eval(sampleCoord);
            }
        """

        internal data class MorphFrame(
            val bodyLeft: Float,
            val bodyTop: Float,
            val bodyRight: Float,
            val bodyBottom: Float,
            val bodyRadius: Float,
            val orbX: Float,
            val orbY: Float,
            val orbRadius: Float,
            val orbBlendPx: Float,
            val contentAlpha: Float,
            val contentBlurDp: Float,
            val contentRefract: Float,
            val echoAlpha: Float,
            val echoBlurDp: Float,
            val echoScale: Float,
            val echoCenterX: Float,
            val echoCenterY: Float,
        )

        private const val DARK_SCRIM_ALPHA = 38
        private const val LIGHT_SCRIM_ALPHA = 22
        private const val DARK_HIGHLIGHT_FILL = 0x24FFFFFF
        private const val LIGHT_HIGHLIGHT_FILL = 0x181A1A2E
        internal const val DEFAULT_DARK_PANEL_TINT: Int = 0x5C12131A
        internal const val DEFAULT_LIGHT_PANEL_TINT: Int = 0x94F8F7FC.toInt()
        private const val DARK_DANGER_COLOR = 0xFFFF6B70.toInt()
        private const val LIGHT_DANGER_COLOR = 0xFFD9383E.toInt()

        internal fun resolveMenuMaterial(
            explicitMaterial: WitLiquidGlassView.Material?,
            anchorMaterial: WitLiquidGlassView.Material?,
        ): WitLiquidGlassView.Material =
            explicitMaterial ?: anchorMaterial ?: WitLiquidGlassView.Material.REGULAR

        internal fun resolveMenuClearDimming(
            explicitDimming: Float?,
            anchorDimming: Float?,
            resolvedMaterial: WitLiquidGlassView.Material,
            isDark: Boolean,
        ): Float = (
            explicitDimming
                ?: anchorDimming
                ?: if (resolvedMaterial == WitLiquidGlassView.Material.CLEAR && isDark) 0.18f else 0f
            ).coerceIn(0f, 1f)

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

        private const val OVERLAY_HOST_TAG = "WitLiquidGlassOverlayHost"

        /**
         * Opens a Liquid Glass morphing menu anchored to [anchor].
         * Automatically locates an existing [WitLiquidGlassLayout] in ancestry or attaches a lightweight
         * full-screen overlay host to the parent Activity's window content.
         */
        public fun show(
            anchor: View,
            block: Builder.() -> Unit,
        ): WitLiquidGlassMenu? {
            findAncestorHost(anchor)?.let { return show(it, anchor, block) }

            val activity = findActivity(anchor.context) ?: return null
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val existingHost = content.findViewWithTag<WitLiquidGlassLayout>(OVERLAY_HOST_TAG)
            if (existingHost != null) {
                val existing = existingHost.getTag(R.id.wit_liquid_glass_menu_active) as? WitLiquidGlassMenu
                if (existing != null && existing.state != State.FINISHED) {
                    val sameAnchor = existing.anchor === anchor
                    if (sameAnchor && (existing.state == State.ATTACHED || existing.state == State.OPENING_OR_OPEN)) {
                        existing.dismiss(animated = true)
                        return null
                    }
                    existing.dismiss(animated = false)
                } else if (existingHost.childCount == 0) {
                    existingHost.backdropSiblingParent = null
                    content.removeView(existingHost)
                }
            }

            val builder = Builder().apply(block)
            val entries = builder.buildEntries()
            if (entries.none { it is Entry.Action || it is Entry.Toggle || it is Entry.Submenu }) return null

            val host = acquireOverlayHost(activity, content)
            val created = WitLiquidGlassMenu(host, anchor, entries, builder.buildConfig())
                .apply { showInternal() }
                .takeUnless { it.state == State.FINISHED }
            if (created == null && host.childCount == 0) {
                host.backdropSiblingParent = null
                (host.parent as? ViewGroup)?.removeView(host)
            }
            return created
        }

        private fun findAncestorHost(anchor: View): WitLiquidGlassLayout? {
            var cur: ViewParent? = anchor.parent
            while (cur != null) {
                if (cur is WitLiquidGlassLayout) return cur
                cur = cur.parent
            }
            return null
        }

        private fun acquireOverlayHost(activity: Activity, content: ViewGroup): WitLiquidGlassLayout {
            var host = content.findViewWithTag<WitLiquidGlassLayout>(OVERLAY_HOST_TAG)
            if (host == null) {
                host = WitLiquidGlassLayout(activity).apply {
                    tag = OVERLAY_HOST_TAG
                    backdropSiblingParent = content
                }
                content.addView(
                    host,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                if (content.width > 0 && content.height > 0 && host.width <= 0) {
                    host.measure(
                        View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY),
                    )
                    host.layout(0, 0, content.width, content.height)
                }
            } else {
                host.quality = WitLiquidGlassQuality.defaultQuality
                host.backdropSiblingParent = content
                host.invalidateBackdrop()
            }
            return host
        }

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
            if (entries.none { it is Entry.Action || it is Entry.Toggle || it is Entry.Submenu }) return null

            return WitLiquidGlassMenu(host, anchor, entries, builder.buildConfig())
                .apply { showInternal() }
                .takeUnless { it.state == State.FINISHED }
        }

        internal fun computeScrimAlpha(
            progress: Float,
            isDark: Boolean,
            dimBackground: Boolean,
        ): Int {
            if (!dimBackground) return 0
            val maxAlpha = if (isDark) DARK_SCRIM_ALPHA else LIGHT_SCRIM_ALPHA
            return (maxAlpha * smoothstep(0f, 0.55f, progress)).roundToInt().coerceIn(0, 255)
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

        private fun springStep(t: Float, omega: Float, zeta: Float, v0: Float = 0f): Float {
            if (t <= 0f) return 0f
            val td = t.toDouble()
            val om = omega.toDouble()
            val z = zeta.toDouble()
            val v = v0.toDouble()
            val value = when {
                z < 0.999 -> {
                    val wd = om * sqrt(1.0 - z * z)
                    val e = exp(-z * om * td)
                    1.0 + e * (-cos(wd * td) + ((v - z * om) / wd) * sin(wd * td))
                }
                z > 1.001 -> {
                    val sq = sqrt(z * z - 1.0)
                    val r1 = -om * (z - sq)
                    val r2 = -om * (z + sq)
                    val c1 = (v + r2) / (r1 - r2)
                    val c2 = -1.0 - c1
                    1.0 + c1 * exp(r1 * td) + c2 * exp(r2 * td)
                }
                else -> {
                    val e = exp(-om * td)
                    1.0 - e * (1.0 + (om - v) * td)
                }
            }
            return value.toFloat()
        }

        internal fun evaluateOpenMorph(
            progress: Float,
            menuW: Float,
            menuH: Float,
            anchorL: Float,
            anchorT: Float,
            anchorW: Float,
            anchorH: Float,
            endRadiusPx: Float,
            density: Float,
            anchorOnRight: Boolean,
            expandingDown: Boolean,
            patchMargin: Float,
        ): MorphFrame {
            val p = progress.coerceIn(0f, 1f)
            val t = p * OPEN_DURATION_SEC
            val acx = anchorL + anchorW * 0.5f
            val acy = anchorT + anchorH * 0.5f
            val mcx = menuW * 0.5f
            val mcy = menuH * 0.5f
            val anchorR = minOf(anchorW, anchorH) * 0.5f
            val dirX = if (anchorOnRight) -1f else 1f
            val dirY = if (expandingDown) 1f else -1f

            val endSnap = smootherstep(0.46f, OPEN_DURATION_SEC, t)

            // Size growth: Apple iOS 26 spring (response ~0.34s, damping ~0.70-0.71) with gentle
            // launch inertia -> peaks ~+2.9% width / +2.4% height overshoot around 250ms.
            val wProg = lerp(springStep(t, 18.4f, 0.70f, -2.6f), 1f, endSnap)
            val hProg = lerp(springStep(t, 18.2f, 0.71f, -1.2f), 1f, endSnap)
            val maxOverW = minOf(menuW * 0.036f, 10f * density)
            val maxOverH = minOf(menuH * 0.032f, 10f * density)
            val curW = lerp(anchorW, menuW, wProg).coerceIn(anchorW * 0.90f, menuW + maxOverW)
            val curH = lerp(anchorH, menuH, hProg).coerceIn(anchorH * 0.90f, menuH + maxOverH)

            // Center X: launches ahead of width growth -> pulls anchor edge inward ~13dp at 50ms
            // and delivers the crisp -6.7dp far-edge overshoot at 250ms.
            val cxProg = lerp(springStep(t, 17.5f, 0.71f, 3.2f), 1f, endSnap)
            val rawCx = lerp(acx, mcx, cxProg)

            // Center Y: fast gravity momentum pour peaking ~+37dp around 112ms (+46dp top sag at
            // 100ms, +17dp bottom overshoot at 184ms), then gently overshooting -2dp top at 335ms.
            // Height-invariant cap keeps short (1-item) and tall (12-item) menus safely inside patchMargin.
            val cyBaseProg = lerp(springStep(t, 18.8f, 0.78f, 4.5f), 1f, endSnap)
            val uSag = t / 0.112f
            val sagEnv = if (uSag > 0f) {
                (uSag.pow(1.55f) * exp(1.55f * (1f - uSag)) * cos(minOf(PI.toFloat() * 0.56f, t * 5.2f)))
            } else {
                0f
            }
            val heightHeadroom = (menuH - anchorH * 0.35f).coerceAtLeast(16f * density)
            val sagAmp = minOf(anchorH * 0.86f, 38f * density, heightHeadroom * 0.35f)
            val rawCy = lerp(acy, mcy, cyBaseProg) + dirY * sagAmp * sagEnv * (1f - endSnap)

            val curL = clampMorphStart(rawCx - curW * 0.5f, curW, menuW, patchMargin)
            val curR = curL + curW
            val curT = clampMorphStart(rawCy - curH * 0.5f, curH, menuH, patchMargin)
            val curB = curT + curH
            val clampedCx = (curL + curR) * 0.5f
            val clampedCy = (curT + curB) * 0.5f

            // Corner radius holds full plump capsule through ~105ms, then relaxes smoothly to 32dp by 330ms.
            val capR = minOf(curW, curH) * 0.5f
            val relax = smootherstep(0.095f, 0.330f, t)
            val bodyR = lerp(capR, minOf(endRadiusPx, capR), relax).coerceAtLeast(0f)

            // Initial diagonal teardrop shoulder (active during 5..75ms launch).
            val tailIn = smoothstep(0.003f, 0.016f, t)
            val tailOut = 1f - smootherstep(0.038f, 0.075f, t)
            val tailW = tailIn * tailOut
            val orbX: Float
            val orbY: Float
            val orbR: Float
            val orbBlendPx: Float
            if (tailW > 0.001f) {
                val lag = smoothstep(0f, 0.070f, t)
                orbX = lerp(acx + dirX * (3.5f * density), clampedCx - dirX * (curW * 0.16f), lag)
                orbY = lerp(acy + dirY * (0.5f * density), clampedCy - dirY * (curH * 0.18f), lag)
                orbR = lerp(anchorR * 0.68f, minOf(curW, curH) * 0.22f, lag) * (0.70f + 0.30f * tailOut)
                orbBlendPx = 16f * density * tailW
            } else {
                orbX = 0f
                orbY = 0f
                orbR = 0f
                orbBlendPx = 0f
            }

            // Menu content stays hidden inside empty liquid droplet through 70ms, emerges blurred
            // through 70..215ms, sharpens to 0dp blur by 245ms, and refracts around curved bubble bevel.
            val contentAlpha = smoothstep(0.070f, 0.215f, t)
            val contentBlurDp = CONTENT_OPEN_MAX_BLUR_DP * (1f - smoothstep(0.080f, 0.245f, t)).pow(1.45f)
            val refractIn = smoothstep(0.085f, 0.155f, t)
            val refractOut = 1f - smootherstep(0.185f, 0.255f, t)
            val contentRefract = (refractIn * refractOut * 0.85f).coerceIn(0f, 1f)

            // Anchor dots travel with the launching droplet and blur/dissolve rapidly in ~48ms.
            val echoFade = smoothstep(0.004f, 0.048f, t)
            val echoAlpha = 1f - echoFade
            val echoScale = lerp(1f, 0.72f, echoFade)
            val echoBlurDp = if (echoFade in 0.01f..0.99f) ECHO_OPEN_MAX_BLUR_DP * echoFade else 0f
            val echoDrift = smoothstep(0f, 0.048f, t)
            val echoCx = lerp(acx, acx + dirX * (15f * density), echoDrift)
            val echoCy = lerp(acy, acy + dirY * (7f * density), echoDrift)

            return MorphFrame(
                bodyLeft = curL,
                bodyTop = curT,
                bodyRight = curR,
                bodyBottom = curB,
                bodyRadius = bodyR,
                orbX = orbX,
                orbY = orbY,
                orbRadius = orbR,
                orbBlendPx = orbBlendPx,
                contentAlpha = contentAlpha,
                contentBlurDp = contentBlurDp,
                contentRefract = contentRefract,
                echoAlpha = echoAlpha,
                echoBlurDp = echoBlurDp,
                echoScale = echoScale,
                echoCenterX = echoCx,
                echoCenterY = echoCy,
            )
        }

        internal fun evaluateCloseMorph(
            closeProgress: Float,
            menuW: Float,
            menuH: Float,
            anchorL: Float,
            anchorT: Float,
            anchorW: Float,
            anchorH: Float,
            endRadiusPx: Float,
            density: Float,
            anchorOnRight: Boolean,
            expandingDown: Boolean,
            patchMargin: Float,
        ): MorphFrame {
            val cp = closeProgress.coerceIn(0f, 1f)
            val t = cp * CLOSE_DURATION_SEC
            val acx = anchorL + anchorW * 0.5f
            val acy = anchorT + anchorH * 0.5f
            val mcx = menuW * 0.5f
            val mcy = menuH * 0.5f
            val anchorR = minOf(anchorW, anchorH) * 0.5f
            val dirX = if (anchorOnRight) -1f else 1f
            val dirY = if (expandingDown) 1f else -1f

            // Menu content blurs and dissolves rapidly within the first ~62ms, leaving pure glass.
            val contentAlpha = 1f - smoothstep(0.005f, 0.062f, t)
            val contentBlurDp = if (contentAlpha > 0.005f) {
                CONTENT_CLOSE_MAX_BLUR_DP * smoothstep(0f, 0.055f, t)
            } else {
                0f
            }
            val contentRefract = if (contentAlpha > 0.005f) {
                smoothstep(0.004f, 0.045f, t) * 0.70f
            } else {
                0f
            }

            // Phase B: Anchor orb emerges from body's upper-anchor shoulder at t=118ms, pulls into
            // the concave gourd/peanut metaball bridge, overshoots -7.4dp past anchor rest at 230ms,
            // and elastically springs back to exact rest at 500ms.
            val hasOrbPhase = t >= 0.118f
            val orbX: Float
            val orbY: Float
            val orbR: Float
            val orbBlendPx: Float
            if (hasOrbPhase) {
                val ot = t - 0.118f
                val orbGrow = lerp(
                    1f - exp(-(ot / 0.052f)),
                    1f,
                    smootherstep(0.22f, 0.34f, ot),
                )
                val orbSwell = 1f + 0.015f * sin(PI.toFloat() * ((t - 0.33f) / 0.17f).coerceIn(0f, 1f))
                orbR = anchorR * orbGrow * orbSwell

                val oxOffset = 18f * density * exp(-(ot / 0.082f)) * (1f - smoothstep(0.22f, 0.30f, ot))
                orbX = acx + dirX * oxOffset

                val uRec = ot / 0.115f
                val overshootBump = if (uRec > 0f) {
                    7.6f * density * (uRec.pow(1.28f) * exp(1.28f * (1f - uRec))) *
                        (1f - smootherstep(0.32f, CLOSE_DURATION_SEC - 0.118f, ot))
                } else {
                    0f
                }
                val startDrop = 26f * density * exp(-(ot / 0.023f))
                val recoilOffset = (startDrop - overshootBump).coerceIn(-patchMargin * 0.5f, patchMargin * 1.4f)
                orbY = acy + dirY * recoilOffset

                val kIn = smoothstep(0.118f, 0.145f, t)
                val kMid = lerp(38f * density, 20f * density, smoothstep(0.155f, 0.235f, t))
                val kOut = 1f - smootherstep(0.260f, 0.305f, t)
                orbBlendPx = kMid * kIn * kOut
            } else {
                orbX = acx
                orbY = acy
                orbR = 0f
                orbBlendPx = 0f
            }

            // Anchor dots emerge right inside the moving top-right orb during 140..285ms and ride
            // the orb's elastic recoil back to exact resting anchor center at 500ms.
            val echoIn = smoothstep(0.140f, 0.285f, t)
            val echoAlpha = echoIn
            val echoScale = if (hasOrbPhase) (orbR / anchorR.coerceAtLeast(1f)).coerceIn(0.65f, 1.02f) else 0.65f
            val echoBlurDp = if (t in 0.140f..0.255f) {
                ECHO_CLOSE_MAX_BLUR_DP * (1f - smoothstep(0.140f, 0.255f, t))
            } else {
                0f
            }
            val echoCx = if (hasOrbPhase) orbX else acx
            val echoCy = if (hasOrbPhase) orbY else acy

            // After t >= 0.305s, the shrinking body is 100% absorbed into the anchor orb: promote
            // orb to the primary morph circle (zero metaball tail) for the final recoil settle.
            if (t >= 0.305f) {
                val orbDia = orbR * 2f
                val oL = clampMorphStart(orbX - orbR, orbDia, menuW, patchMargin)
                val oT = clampMorphStart(orbY - orbR, orbDia, menuH, patchMargin)
                return MorphFrame(
                    bodyLeft = oL,
                    bodyTop = oT,
                    bodyRight = oL + orbDia,
                    bodyBottom = oT + orbDia,
                    bodyRadius = orbR,
                    orbX = 0f,
                    orbY = 0f,
                    orbRadius = 0f,
                    orbBlendPx = 0f,
                    contentAlpha = 0f,
                    contentBlurDp = 0f,
                    contentRefract = 0f,
                    echoAlpha = echoAlpha,
                    echoBlurDp = echoBlurDp,
                    echoScale = echoScale,
                    echoCenterX = oL + orbR,
                    echoCenterY = oT + orbR,
                )
            }

            // Phase A: Body shrinks smoothly into the orb over 0..305ms with initial gravity dip.
            val absorbOut = 1f - smootherstep(0.255f, 0.305f, t)
            val shrinkW = exp(-(t / 0.170f).pow(1.66f)) * absorbOut
            val shrinkH = exp(-(t / 0.178f).pow(1.72f)) * absorbOut
            val bodyW = (menuW * shrinkW).coerceAtLeast(0f)
            val bodyH = (menuH * shrinkH).coerceAtLeast(0f)

            val cxT = lerp(1f - exp(-(t / 0.158f).pow(1.70f)), 1f, smoothstep(0.25f, 0.31f, t))
            val bodyCx = lerp(mcx, acx + dirX * (3.5f * density), cxT)

            val cyUp = lerp(1f - exp(-(t / 0.185f).pow(2.10f)), 1f, smoothstep(0.26f, 0.31f, t))
            val uDip = t / 0.060f
            val dipEnv = if (uDip > 0f) uDip.pow(1.15f) * exp(1.15f * (1f - uDip)) else 0f
            val dipHeadroom = (menuH - anchorH * 0.35f).coerceAtLeast(16f * density)
            val dipAmp = minOf(anchorH * 0.80f, 35.5f * density, dipHeadroom * 0.32f)
            val bodyCy = lerp(mcy, acy + dirY * (5f * density), cyUp) + dirY * dipAmp * dipEnv

            val curL = clampMorphStart(bodyCx - bodyW * 0.5f, bodyW, menuW, patchMargin)
            val curR = curL + bodyW
            val curT = clampMorphStart(bodyCy - bodyH * 0.5f, bodyH, menuH, patchMargin)
            val curB = curT + bodyH

            val capR = minOf(bodyW, bodyH) * 0.5f
            val inflate = smoothstep(0f, 0.145f, t)
            val bodyR = lerp(minOf(endRadiusPx, capR), capR, inflate).coerceAtLeast(0f)

            return MorphFrame(
                bodyLeft = curL,
                bodyTop = curT,
                bodyRight = curR,
                bodyBottom = curB,
                bodyRadius = bodyR,
                orbX = orbX,
                orbY = orbY,
                orbRadius = orbR,
                orbBlendPx = orbBlendPx,
                contentAlpha = contentAlpha,
                contentBlurDp = contentBlurDp,
                contentRefract = contentRefract,
                echoAlpha = echoAlpha,
                echoBlurDp = echoBlurDp,
                echoScale = echoScale,
                echoCenterX = echoCx,
                echoCenterY = echoCy,
            )
        }

        private fun lerpMorphFrame(a: MorphFrame, b: MorphFrame, f: Float): MorphFrame = MorphFrame(
            bodyLeft = lerp(a.bodyLeft, b.bodyLeft, f),
            bodyTop = lerp(a.bodyTop, b.bodyTop, f),
            bodyRight = lerp(a.bodyRight, b.bodyRight, f),
            bodyBottom = lerp(a.bodyBottom, b.bodyBottom, f),
            bodyRadius = lerp(a.bodyRadius, b.bodyRadius, f),
            orbX = if (a.orbRadius <= 0.5f) b.orbX else if (b.orbRadius <= 0.5f) a.orbX else lerp(a.orbX, b.orbX, f),
            orbY = if (a.orbRadius <= 0.5f) b.orbY else if (b.orbRadius <= 0.5f) a.orbY else lerp(a.orbY, b.orbY, f),
            orbRadius = lerp(a.orbRadius, b.orbRadius, f),
            orbBlendPx = lerp(a.orbBlendPx, b.orbBlendPx, f),
            contentAlpha = lerp(a.contentAlpha, b.contentAlpha, f),
            contentBlurDp = lerp(a.contentBlurDp, b.contentBlurDp, f),
            contentRefract = lerp(a.contentRefract, b.contentRefract, f),
            echoAlpha = lerp(a.echoAlpha, b.echoAlpha, f),
            echoBlurDp = lerp(a.echoBlurDp, b.echoBlurDp, f),
            echoScale = lerp(a.echoScale, b.echoScale, f),
            echoCenterX = lerp(a.echoCenterX, b.echoCenterX, f),
            echoCenterY = lerp(a.echoCenterY, b.echoCenterY, f),
        )

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction

        internal fun findActivity(context: Context): Activity? {
            var ctx: Context? = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }

        internal fun computeWeightedDerivedStartColor(
            palette: V3Palette,
            isDark: Boolean,
        ): Int {
            val derivedBase = if (isDark == palette.isDark) palette.cardFill else V3Palette(palette.primary, isDark).cardFill
            val primary = palette.primary
            // 主题派生色在当前日/夜模式下的加权微调：
            // 夜间微调：融入 70% 主题色光感，形成深邃晶莹的夜色水滴质感，与顶栏拉开反差；
            // 日间微调：融入 80% 主题色光感，形成清透水润的日间液滴质感。
            val weight = if (isDark) 0.70f else 0.80f
            return blendColors(derivedBase, primary, weight)
        }

        internal fun computeSurfaceFill(
            progress: Float,
            startColor: Int,
            derivedColor: Int,
            targetColor: Int,
            isBorderless: Boolean,
            closing: Boolean = false,
        ): Int {
            if (!isBorderless) return targetColor
            val p = progress.coerceIn(0f, 1f)
            return if (!closing) {
                // 展开：前 12% 迅速变化为主题派生色在当前模式下的加权微调色（快速与底色拉开视觉差），随后 C² 连续过渡到目标卡片色
                if (p <= 0.12f) {
                    val f1 = smoothstep(0f, 0.12f, p)
                    blendColors(startColor, derivedColor, f1)
                } else {
                    val f2 = smootherstep(0.12f, 0.85f, p)
                    blendColors(derivedColor, targetColor, f2)
                }
            } else {
                // 收回：从目标卡片色平滑回到主题派生色在当前模式下的加权微调色；最后一点点（0..0.22f）平缓减速缓慢消融回 Toolbar 原色
                if (p > 0.22f) {
                    val f2 = smootherstep(0.22f, 0.88f, p)
                    blendColors(derivedColor, targetColor, f2)
                } else {
                    val u = (p / 0.22f).coerceIn(0f, 1f)
                    val slowFactor = u * u // 缓入零点，最后一段极尽缓慢柔和
                    blendColors(startColor, derivedColor, slowFactor)
                }
            }
        }

        internal fun computeSurfaceFill(
            progress: Float,
            startColor: Int,
            targetColor: Int,
            isBorderless: Boolean,
        ): Int =
            if (isBorderless) {
                val factor = smootherstep(0.06f, 0.88f, progress.coerceIn(0f, 1f))
                blendColors(startColor, targetColor, factor)
            } else {
                targetColor
            }

        internal fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
            val f = ratio.coerceIn(0f, 1f)
            val inv = 1f - f
            val a = (((color1 ushr 24) and 0xFF) * inv + ((color2 ushr 24) and 0xFF) * f).roundToInt().coerceIn(0, 255)
            val r1 = (color1 ushr 16) and 0xFF
            val g1 = (color1 ushr 8) and 0xFF
            val b1 = color1 and 0xFF
            val r2 = (color2 ushr 16) and 0xFF
            val g2 = (color2 ushr 8) and 0xFF
            val b2 = color2 and 0xFF
            val r = kotlin.math.sqrt(inv * r1 * r1 + f * r2 * r2).roundToInt().coerceIn(0, 255)
            val g = kotlin.math.sqrt(inv * g1 * g1 + f * g2 * g2).roundToInt().coerceIn(0, 255)
            val b = kotlin.math.sqrt(inv * b1 * b1 + f * b2 * b2).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        internal fun computeRimAlpha(progress: Float, isBorderless: Boolean): Float =
            if (isBorderless) smootherstep(0.08f, 0.84f, progress.coerceIn(0f, 1f)) else 1f

        internal fun computePlatterAlpha(progress: Float, isBorderless: Boolean): Float =
            1f

        internal fun computeElevationFraction(progress: Float, isBorderless: Boolean): Float =
            if (isBorderless) smootherstep(0.12f, 0.82f, progress.coerceIn(0f, 1f)) else 1f

        internal fun computeCloseShrink(progress: Float, closing: Boolean, isBorderless: Boolean): Float =
            if (closing && isBorderless) smootherstep(0f, 0.16f, progress.coerceIn(0f, 1f)) else 1f

        internal fun computeAnimAlpha(progress: Float, closing: Boolean, isBorderless: Boolean): Float =
            when {
                closing && isBorderless -> smootherstep(0f, 0.12f, progress.coerceIn(0f, 1f))
                !closing && isBorderless -> smootherstep(0f, 0.06f, progress.coerceIn(0f, 1f))
                else -> 1f
            }

        internal fun computeCenterProgress(progress: Float, closing: Boolean = false): Float =
            if (closing) {
                // 收回时保持同展开对称的斜向流体收敛轨迹，避免过早锁死在按钮下方聚成一坨
                smootherstep(0.04f, 0.70f, progress.coerceIn(0f, 1f))
            } else {
                smoothstep(0f, 0.45f, progress.coerceIn(0f, 1f))
            }

        internal fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        internal fun smootherstep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * t * (t * (t * 6f - 15f) + 10f)
        }
    }
}
