package ceui.pixiv.witstudio.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackdrop
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBudget
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassMorphTail
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassPaint
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassPhysics
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassRenderer
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassShader
import ceui.pixiv.witstudio.widget.internal.buildLiquidGlassMorphPath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A glass surface for floating controls. Add it above the source as a direct child of
 * [WitLiquidGlassLayout]. Add standard buttons, labels, or icons as children of this view.
 * The material samples the backdrop only and leaves foreground content sharp.
 */
public class WitLiquidGlassView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    public enum class Material {
        /** Translucent, with tone-dependent scattering and contrast. */
        REGULAR,
        /** Higher transmission for imagery. The caller controls any dimming via [clearDimming]. */
        CLEAR,
    }

    public enum class Tone { THEME, LIGHT, DARK }

    public var material: Material? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateMaterial()
        }

    public val effectiveMaterial: Material
        get() = material ?: if (effectiveQuality == WitLiquidGlassQuality.ULTRA) Material.CLEAR else Material.REGULAR

    public var tone: Tone = Tone.THEME
        set(value) {
            if (field == value) return
            field = value
            resolveBaseFill()
        }

    /** Radius in dp, capped at half the short side. Infinity makes a capsule or a circle. */
    public var cornerRadiusDp: Float = Float.POSITIVE_INFINITY
        set(value) {
            require(value >= 0f) { "cornerRadiusDp must be non-negative" }
            if (field == value) return
            field = value
            invalidateMaterial()
        }

    /** Optional color wash. Alpha determines the strength. */
    @get:ColorInt
    @setparam:ColorInt
    public var tintColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidateMaterial()
        }

    /** Optional local darkening in the range 0–1, applied to [Material.CLEAR]. */
    public var clearDimming: Float = 0f
        set(value) {
            require(value in 0f..1f) { "clearDimming must be in 0..1" }
            if (field == value) return
            field = value
            invalidateMaterial()
        }

    /** React to touches accepted by the surface or its child controls. */
    public var isGlassInteractive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) resetPress()
        }

    /** 显式指定本表面的渲染效果等级；null 时继承宿主 [WitLiquidGlassLayout.quality] 或全局默认。 */
    public var quality: WitLiquidGlassQuality? = null
        set(value) {
            if (field == value) return
            field = value
            if (!effectiveQuality.isLiveEnabled) {
                resetPress()
            }
            if (Build.VERSION.SDK_INT >= 31) {
                discardRenderer()
            }
            invalidateMaterial()
            (parent as? View)?.invalidate()
        }

    public val effectiveQuality: WitLiquidGlassQuality
        get() = quality ?: (parent as? WitLiquidGlassHost)?.quality ?: WitLiquidGlassQuality.defaultQuality

    /** Foreground color for the resolved [tone]. Set it on the controls this surface contains. */
    @get:ColorInt
    public val contentColor: Int
        get() = if (isDark) Color.WHITE else 0xFF1A1A2E.toInt()

    private var palette = V3Palette.from(context)
    private var baseFill = palette.cardFill
    private val isDark: Boolean
        get() = when (tone) {
            Tone.THEME -> palette.isDark
            Tone.LIGHT -> false
            Tone.DARK -> true
        }
    private val density: Float get() = resources.displayMetrics.density
    private val radius: Float get() = (cornerRadiusDp * density).coerceAtMost(minOf(width, height) / 2f)

    /** Enables larger transient panel budget and softer backdrop scattering for floating menus. */
    internal var isLargeSurface: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateOptics()
        }

    /**
     * Controls the overall opacity of the glass platter, backdrop and rim without affecting child views.
     * Used for smooth material condensation transitions when expanding from borderless buttons.
     */
    internal var platterAlpha: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * Optional explicit surface base fill color. When non-null, overrides [V3Palette.cardFill].
     * Used for smooth dynamic color transitions from toolbar background to floating card panel.
     */
    @get:ColorInt
    @setparam:ColorInt
    internal var surfaceFillColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            resolveBaseFill()
        }

    internal var rimAlpha: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            materialPaint.rimAlpha = clamped
            invalidate()
        }

    private var morphActive = false
    private var morphLeft = 0f
    private var morphTop = 0f
    private var morphRight = 0f
    private var morphBottom = 0f
    private var morphRadius = 0f
    private val morphTail = WitLiquidGlassMorphTail()
    private val morphClipPath = Path()
    private val morphScratchPath = Path()
    private val morphArcRect = RectF()
    private val platterBounds = RectF()

    private val materialPaint = WitLiquidGlassPaint()
    private var renderer: WitLiquidGlassRenderer? = null
    private var materialDirty = true
    private var opticsDirty = true

    internal var overlay: WitLiquidGlassOverlay? = null
        set(value) {
            if (field === value) return
            field = value
            if (value != null) resetPress()
            invalidate()
        }

    private var touchPointer = MotionEvent.INVALID_POINTER_ID
    private var touchX = 0f
    private var touchY = 0f
    private var pressDownUnscaledX = 0f
    private var pressDownUnscaledY = 0f
    private var press = 0f
    private var stretch = 0f
    private var pressTarget = 0f
    private var pressDownAtMs = 0L
    private var pendingReleaseAtMs = 0L
    private var interactiveTransformApplied = false
    private var appliedPullTx = 0f
    private var appliedPullTy = 0f
    private var pressLoopRunning = false
    private var lastPressFrameMs = 0L
    private val pressPhysics = WitLiquidGlassPhysics.ControlPressPhysics()
    private val scratchControlTransform = WitLiquidGlassPhysics.MutableControlTransform()
    private val drawChildrenCallback: (Canvas) -> Unit = { super.dispatchDraw(it) }
    private val pressFrameRunnable = object : Runnable {
        override fun run() {
            pressLoopRunning = false
            if (!isAttachedToWindow || !isGlassInteractive || overlay?.isActive == true || morphActive) {
                resetPress()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (pendingReleaseAtMs > 0L && now >= pendingReleaseAtMs) {
                pendingReleaseAtMs = 0L
                pressTarget = 0f
                pressPhysics.pressed = false
                pressPhysics.targetPullX = 0f
                pressPhysics.targetPullY = 0f
            }
            val dtSec = if (lastPressFrameMs > 0L) {
                ((now - lastPressFrameMs).coerceAtLeast(1L)) / 1000f
            } else {
                1f / 60f
            }
            lastPressFrameMs = now

            val keepRunning = pressPhysics.step(dtSec) || pendingReleaseAtMs > 0L
            applyInteractivePhysicsState()

            if (keepRunning) {
                schedulePressFrame()
            } else {
                lastPressFrameMs = 0L
            }
        }
    }

    internal companion object {
        internal const val PRESS_STIFFNESS: Float = WitLiquidGlassPhysics.PRESS_STIFFNESS
        internal const val PRESS_DAMPING: Float = WitLiquidGlassPhysics.PRESS_DAMPING
        internal const val RELEASE_STIFFNESS: Float = WitLiquidGlassPhysics.RELEASE_STIFFNESS
        internal const val RELEASE_DAMPING: Float = WitLiquidGlassPhysics.RELEASE_DAMPING
        private const val MIN_TAP_BLOOM_MS: Long = WitLiquidGlassPhysics.MIN_CONTROL_TAP_BLOOM_MS

        internal fun computePressScaleTarget(widthPx: Int, heightPx: Int, density: Float): Float =
            WitLiquidGlassPhysics.computePressScaleTarget(widthPx, heightPx, density)
    }

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (morphActive) {
                    val hasOrb = morphTail.active
                    val bodyW = (morphRight - morphLeft).coerceAtLeast(0f)
                    val bodyH = (morphBottom - morphTop).coerceAtLeast(0f)
                    val orbDia = morphTail.orbRadius * 2f
                    if (hasOrb && minOf(bodyW, bodyH) < orbDia * 0.85f) {
                        val l = (morphTail.orbX - morphTail.orbRadius).roundToInt()
                        val t = (morphTail.orbY - morphTail.orbRadius).roundToInt()
                        val r = (morphTail.orbX + morphTail.orbRadius).roundToInt()
                        val b = (morphTail.orbY + morphTail.orbRadius).roundToInt()
                        if (r > l && b > t) {
                            outline.setRoundRect(l, t, r, b, minOf(r - l, b - t) * 0.5f)
                        } else {
                            outline.setEmpty()
                        }
                    } else {
                        val l = morphLeft.roundToInt()
                        val t = morphTop.roundToInt()
                        val r = morphRight.roundToInt()
                        val b = morphBottom.roundToInt()
                        if (r > l && b > t) {
                            val rad = morphRadius.coerceIn(0f, minOf(r - l, b - t) / 2f)
                            outline.setRoundRect(l, t, r, b, rad)
                        } else {
                            outline.setEmpty()
                        }
                    }
                } else {
                    outline.setRoundRect(0, 0, width, height, radius)
                }
                outline.alpha = if (effectiveQuality == WitLiquidGlassQuality.ULTRA) {
                    if (isDark) 0.10f else 0.18f
                } else {
                    if (isDark) 0.16f else 0.28f
                }
            }
        }

        context.withStyledAttributes(attrs, intArrayOf(android.R.attr.elevation), defStyleAttr) {
            if (!hasValue(0)) elevation = 9f * density
        }

        context.withStyledAttributes(attrs, R.styleable.WitLiquidGlassView, defStyleAttr) {
            val matIdx = getInt(R.styleable.WitLiquidGlassView_wit_glassMaterial, -1)
            if (matIdx >= 0) {
                material = Material.entries.getOrNull(matIdx)
            }
            tone = Tone.entries.getOrElse(getInt(R.styleable.WitLiquidGlassView_wit_glassTone, 0)) { Tone.THEME }
            cornerRadiusDp = getDimension(
                R.styleable.WitLiquidGlassView_wit_glassCornerRadius, Float.POSITIVE_INFINITY,
            ) / density
            tintColor = getColor(R.styleable.WitLiquidGlassView_wit_glassTint, Color.TRANSPARENT)
            clearDimming = getFloat(R.styleable.WitLiquidGlassView_wit_glassClearDimming, 0f)
            isGlassInteractive = getBoolean(R.styleable.WitLiquidGlassView_wit_glassInteractive, false)
            val qIdx = getInt(R.styleable.WitLiquidGlassView_wit_glassQuality, -1)
            if (qIdx >= 0) {
                quality = WitLiquidGlassQuality.fromOrdinal(qIdx)
            }
        }
    }

    internal fun planPatch(canvasEdge: Int): WitLiquidGlassBudget.PatchPlan =
        if (!effectiveQuality.isLiveEnabled) {
            WitLiquidGlassBudget.PatchPlan(0L, 1f)
        } else {
            WitLiquidGlassBudget.planPatch(width, height, density, canvasEdge, isLargeSurface)
        }

    /**
     * Animates the visible glass silhouette inside this view's laid-out bounds without triggering
     * layout passes or re-allocating the offscreen RenderNode patch.
     */
    internal fun setMorphFrame(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadiusPx: Float,
        orbX: Float = 0f,
        orbY: Float = 0f,
        orbRadius: Float = 0f,
        orbBlendPx: Float = 0f,
    ) {
        if (morphActive &&
            morphLeft == left && morphTop == top &&
            morphRight == right && morphBottom == bottom &&
            morphRadius == cornerRadiusPx &&
            morphTail.orbX == orbX && morphTail.orbY == orbY &&
            morphTail.orbRadius == orbRadius && morphTail.blendPx == orbBlendPx
        ) {
            return
        }
        morphActive = true
        morphLeft = left
        morphTop = top
        morphRight = right
        morphBottom = bottom
        morphRadius = cornerRadiusPx
        morphTail.orbX = orbX
        morphTail.orbY = orbY
        morphTail.orbRadius = orbRadius
        morphTail.blendPx = orbBlendPx
        invalidateOutline()
        invalidateOptics()
    }

    internal fun setMorphTouch(x: Float, y: Float, illumination: Float) {
        val clamped = illumination.coerceIn(0f, 1f)
        if (touchX == x && touchY == y && press == clamped && stretch == 0f) return
        touchX = x
        touchY = y
        press = clamped
        stretch = 0f
        invalidateOptics()
    }

    internal fun clearMorphFrame() {
        val hadMorph = morphActive
        val hadTouch = press != 0f || stretch != 0f
        if (!hadMorph && !hadTouch) return
        morphActive = false
        morphTail.clear()
        press = 0f
        stretch = 0f
        if (hadMorph) invalidateOutline()
        invalidateOptics()
    }

    internal fun resetInteraction() {
        resetPress()
    }

    /** Draws the live backdrop behind this view into [canvas] in view-local coordinates (API 31+). */
    internal fun drawLiveBackdropInLocal(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return false
        return renderer?.drawBackdropInLocal(canvas) == true
    }

    /** Returns the recorded live scene backdrop bounds in view-local coordinates (API 31+). */
    internal fun getLiveBackdropBoundsInLocal(outRect: RectF): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return renderer?.getBackdropBoundsInLocal(outRect) == true
    }

    /** Draws only this view's outer glass platter into [canvas] (used by loupe overlays for seamless refraction). */
    internal fun drawGlassPlatter(canvas: Canvas) {
        drawPlatterInternal(canvas)
    }

    internal fun onHostQualityChanged() {
        if (quality != null) return
        if (!effectiveQuality.isLiveEnabled) {
            resetPress()
        }
        if (Build.VERSION.SDK_INT >= 31) {
            discardRenderer()
        }
        invalidateMaterial()
    }

    @RequiresApi(31)
    internal fun updateBackdrop(
        backdrop: WitLiquidGlassBackdrop?,
        parentScrollX: Int,
        parentScrollY: Int,
        renderScale: Float = 1f,
    ) {
        if (backdrop == null || !effectiveQuality.isLiveEnabled || !WitLiquidGlassBackend.BACKDROP.available) {
            discardRenderer()
            return
        }
        if (renderer?.backend?.available == false) discardRenderer()
        val expectedShader = effectiveQuality == WitLiquidGlassQuality.ULTRA && Build.VERSION.SDK_INT >= 33
        if (renderer != null && (renderer is WitLiquidGlassShader) != expectedShader) {
            discardRenderer()
        }
        val current = renderer ?: createRenderer()?.also {
            renderer = it
            opticsDirty = true
        } ?: return
        val hasBackdrop = current.hasBackdrop
        val opticsChanged = WitLiquidGlassBackend.BACKDROP.attempt {
            current.updateBackdrop(this, backdrop, parentScrollX, parentScrollY, density, renderScale)
        }
        if (opticsChanged == null) {
            discardRenderer()
        } else if (opticsChanged) {
            invalidateOptics()
        } else if (hasBackdrop != current.hasBackdrop) {
            invalidate()
        }
    }

    @RequiresApi(31)
    private fun createRenderer(): WitLiquidGlassRenderer? {
        val q = effectiveQuality
        if (!q.isLiveEnabled) return null
        if (q == WitLiquidGlassQuality.ULTRA && Build.VERSION.SDK_INT >= 33) {
            WitLiquidGlassBackend.REFRACTION.attempt { WitLiquidGlassShader() }?.let { return it }
        }
        return WitLiquidGlassBackend.BACKDROP.attempt { WitLiquidGlassRenderer() }
    }

    @RequiresApi(31)
    private fun discardRenderer() {
        val current = renderer ?: return
        renderer = null
        if (current.clearBackdrop()) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (press > 0f || stretch != 0f)) {
            updateTouch(touchX, touchY)
        }
        invalidateMaterial()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = V3Palette.from(context)
        resolveBaseFill()
    }

    private fun resolveBaseFill() {
        val dark = isDark
        val normalFill = if (dark == palette.isDark) palette.cardFill else V3Palette(palette.primary, dark).cardFill
        baseFill = surfaceFillColor ?: normalFill
        invalidateMaterial()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPlatterInternal(canvas)
    }

    private fun drawPlatterInternal(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val pAlpha = platterAlpha
        if (pAlpha <= 0.001f) return

        val scale = density
        val dark = isDark
        val clear = effectiveMaterial == Material.CLEAR
        val effectiveDimming = if (clear && clearDimming <= 0.001f) 0f else clearDimming
        val sLeft = if (morphActive) morphLeft else 0f
        val sTop = if (morphActive) morphTop else 0f
        val sRight = if (morphActive) morphRight else w.toFloat()
        val sBottom = if (morphActive) morphBottom else h.toFloat()
        val activeTail = if (morphActive && morphTail.active) morphTail else null
        if ((sRight <= sLeft || sBottom <= sTop) && activeTail == null) return
        val activeRadius = if (morphActive) {
            morphRadius.coerceIn(0f, minOf((sRight - sLeft).coerceAtLeast(0f), (sBottom - sTop).coerceAtLeast(0f)) / 2f)
        } else {
            radius
        }
        materialPaint.rimAlpha = rimAlpha
        if (materialDirty) {
            materialPaint.update(w, h, scale, baseFill, tintColor, dark, clear, effectiveDimming)
            materialDirty = false
        }
        val needsAlphaLayer = pAlpha < 0.999f
        if (sRight > sLeft && sBottom > sTop) {
            platterBounds.set(sLeft, sTop, sRight, sBottom)
        } else if (activeTail != null) {
            platterBounds.set(
                activeTail.orbX - activeTail.orbRadius,
                activeTail.orbY - activeTail.orbRadius,
                activeTail.orbX + activeTail.orbRadius,
                activeTail.orbY + activeTail.orbRadius,
            )
        }
        if (activeTail != null) {
            val pad = activeTail.orbRadius + activeTail.blendPx
            platterBounds.union(activeTail.orbX - pad, activeTail.orbY - pad)
            platterBounds.union(activeTail.orbX + pad, activeTail.orbY + pad)
        }
        if (needsAlphaLayer) {
            val layerPad = maxOf(2f, scale)
            platterBounds.inset(-layerPad, -layerPad)
        }
        canvas.withTranslation(scrollX.toFloat(), scrollY.toFloat()) {
            val count = if (needsAlphaLayer) {
                saveLayerAlpha(platterBounds, (pAlpha * 255f).roundToInt())
            } else {
                -1
            }
            try {
                if (effectiveQuality == WitLiquidGlassQuality.NONE) {
                    materialPaint.drawSolid(
                        this, sLeft, sTop, sRight, sBottom, activeRadius, activeTail,
                    )
                    return@withTranslation
                }
                if (Build.VERSION.SDK_INT >= 31 && isHardwareAccelerated && canvas.isHardwareAccelerated && effectiveQuality.isLiveEnabled) {
                    val current = renderer
                    if (current != null && current.hasBackdrop) {
                        val drawn = current.backend.attempt {
                            if (opticsDirty) {
                                current.updateOptics(
                                    sLeft, sTop, sRight, sBottom, activeRadius, scale, dark, clear,
                                    isLargeSurface, tintColor, effectiveDimming, touchX, touchY, press, stretch,
                                    activeTail,
                                )
                                opticsDirty = false
                            }
                            current.draw(
                                this, materialPaint, sLeft, sTop, sRight, sBottom, activeRadius,
                                touchX, touchY, press, stretch, activeTail,
                            )
                        }
                        if (drawn != null) return@withTranslation
                        discardRenderer()
                    }
                }
                materialPaint.drawFallback(
                    this, sLeft, sTop, sRight, sBottom, activeRadius, touchX, touchY, press, stretch,
                    activeTail,
                )
            } finally {
                if (count >= 0) {
                    restoreToCount(count)
                }
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val activeOverlay = overlay
        if (activeOverlay != null && activeOverlay.isActive) {
            activeOverlay.dispatchDraw(canvas, drawChildrenCallback)
            return
        }
        if (!morphActive) {
            super.dispatchDraw(canvas)
            return
        }
        val w = (morphRight - morphLeft).coerceAtLeast(0f)
        val h = (morphBottom - morphTop).coerceAtLeast(0f)
        val activeTail = if (morphTail.active) morphTail else null
        if ((w <= 0f || h <= 0f) && activeTail == null) return
        val rad = morphRadius.coerceIn(0f, minOf(w, h) / 2f)
        buildLiquidGlassMorphPath(
            morphClipPath, morphScratchPath, morphArcRect,
            morphLeft, morphTop, morphRight, morphBottom,
            rad, activeTail,
        )
        if (morphClipPath.isEmpty && w > 0f && h > 0f) {
            morphClipPath.addRoundRect(morphLeft, morphTop, morphRight, morphBottom, rad, rad, Path.Direction.CW)
        }
        if (morphClipPath.isEmpty) return
        canvas.withClip(morphClipPath) {
            super.dispatchDraw(this)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val activeOverlay = overlay
        if (activeOverlay != null && activeOverlay.isActive && activeOverlay.onDispatchTouchEvent(event)) {
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (!isGlassInteractive || !isEnabled || effectiveQuality == WitLiquidGlassQuality.NONE) return handled
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (handled) {
                pendingReleaseAtMs = 0L
                touchPointer = event.getPointerId(0)
                pressDownAtMs = SystemClock.uptimeMillis()
                pressDownUnscaledX = toUnscaledLocalX(event.x)
                pressDownUnscaledY = toUnscaledLocalY(event.y)
                pressPhysics.targetPullX = 0f
                pressPhysics.targetPullY = 0f
                updateTouch(pressDownUnscaledX, pressDownUnscaledY)
                animatePress(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(touchPointer)
                if (index >= 0) {
                    val ux = toUnscaledLocalX(event.getX(index))
                    val uy = toUnscaledLocalY(event.getY(index))
                    updateTouch(ux, uy)
                    updateDirectionalPull(ux, uy)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == touchPointer) releaseTouch(cancelled = false)
            }
            MotionEvent.ACTION_UP -> {
                if (touchPointer != MotionEvent.INVALID_POINTER_ID) releaseTouch(cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchPointer != MotionEvent.INVALID_POINTER_ID) releaseTouch(cancelled = true)
            }
        }
        return handled
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (effectiveQuality == WitLiquidGlassQuality.NONE) {
            resetPress()
            return
        }
        if (isGlassInteractive && !isEnabled) {
            resetPress()
        } else if (isGlassInteractive && touchPointer == MotionEvent.INVALID_POINTER_ID && pendingReleaseAtMs == 0L) {
            if (isPressed) {
                pressPhysics.targetPullX = 0f
                pressPhysics.targetPullY = 0f
                updateTouch(width / 2f, height / 2f)
            }
            animatePress(isPressed)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            resetPress()
            overlay?.onWindowVisibilityChanged(visibility)
            if (Build.VERSION.SDK_INT >= 31) renderer?.clearBackdrop()
        } else {
            overlay?.onWindowVisibilityChanged(visibility)
            invalidateOptics()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pressFrameRunnable)
        pressLoopRunning = false
        overlay?.onDetachedFromWindow()
        resetPress()
        if (Build.VERSION.SDK_INT >= 31) discardRenderer()
        super.onDetachedFromWindow()
    }

    private fun invalidateMaterial() {
        materialDirty = true
        invalidateOutline()
        invalidateOptics()
    }

    private fun invalidateOptics() {
        opticsDirty = true
        invalidate()
    }

    private fun toUnscaledLocalX(localX: Float): Float =
        pivotX + (localX - pivotX) * scaleX + appliedPullTx

    private fun toUnscaledLocalY(localY: Float): Float =
        pivotY + (localY - pivotY) * scaleY + appliedPullTy

    private fun updateTouch(x: Float, y: Float) {
        // Project a distant pointer to the rim along the pull direction. The primary pointer
        // keeps the elastic pressure until UP or CANCEL, regardless of distance from the rim.
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val dx = x - halfWidth
        val dy = y - halfHeight
        val distance = maxOf(1f, abs(dx) / maxOf(halfWidth, 1f), abs(dy) / maxOf(halfHeight, 1f))
        val localX = halfWidth + dx / distance
        val localY = halfHeight + dy / distance
        if (touchX == localX && touchY == localY) return
        touchX = localX
        touchY = localY
        invalidateOptics()
    }

    private fun updateDirectionalPull(unscaledX: Float, unscaledY: Float) {
        if (width <= 0 || height <= 0 || !motionEnabled()) return
        val dx = unscaledX - pressDownUnscaledX
        val dy = unscaledY - pressDownUnscaledY
        // Small deadzone keeps stationary taps centered while larger pulls create tactile liquid lean.
        val deadzone = 4f * density
        val dist = hypot(dx, dy)
        val newTx: Float
        val newTy: Float
        if (dist <= deadzone) {
            newTx = 0f
            newTy = 0f
        } else {
            val activeScale = (dist - deadzone) / dist
            val pullScale = maxOf(34f * density, minOf(width, height) * 0.72f)
            newTx = WitLiquidGlassPhysics.rubberBandUnit(dx * activeScale, pullScale)
            newTy = WitLiquidGlassPhysics.rubberBandUnit(dy * activeScale, pullScale)
        }
        if (abs(newTx - pressPhysics.targetPullX) > 0.0005f || abs(newTy - pressPhysics.targetPullY) > 0.0005f) {
            pressPhysics.targetPullX = newTx
            pressPhysics.targetPullY = newTy
            startPressLoop()
        }
    }

    private fun releaseTouch(cancelled: Boolean) {
        touchPointer = MotionEvent.INVALID_POINTER_ID
        val elapsed = SystemClock.uptimeMillis() - pressDownAtMs
        if (!cancelled && motionEnabled() && pressTarget > 0f && elapsed < MIN_TAP_BLOOM_MS && stretch < 0.62f) {
            pendingReleaseAtMs = pressDownAtMs + MIN_TAP_BLOOM_MS
            startPressLoop()
            return
        }
        pendingReleaseAtMs = 0L
        animatePress(false)
    }

    private fun resetPress() {
        removeCallbacks(pressFrameRunnable)
        pressLoopRunning = false
        lastPressFrameMs = 0L
        pendingReleaseAtMs = 0L
        touchPointer = MotionEvent.INVALID_POINTER_ID
        pressTarget = 0f
        pressPhysics.reset()
        setOpticalPressure(0f, 0f)
        clearInteractiveTransform()
    }

    private fun animatePress(pressed: Boolean) {
        val target = if (pressed) 1f else 0f
        if (pressTarget == target && pendingReleaseAtMs == 0L) return
        pressTarget = target
        pendingReleaseAtMs = 0L
        if (!motionEnabled()) {
            removeCallbacks(pressFrameRunnable)
            pressLoopRunning = false
            lastPressFrameMs = 0L
            pressPhysics.reset()
            setOpticalPressure(target, 0f)
            clearInteractiveTransform()
            return
        }
        pressPhysics.pressed = pressed
        if (!pressed) {
            pressPhysics.targetPullX = 0f
            pressPhysics.targetPullY = 0f
        } else if (!pressLoopRunning && pressPhysics.swell.value == 0f) {
            pressPhysics.swell.value = 0.08f
            applyInteractivePhysicsState()
        }
        startPressLoop()
    }

    private fun startPressLoop() {
        if (!pressLoopRunning) {
            lastPressFrameMs = SystemClock.uptimeMillis()
            schedulePressFrame()
        }
    }

    private fun schedulePressFrame() {
        if (pressLoopRunning) return
        pressLoopRunning = true
        postOnAnimation(pressFrameRunnable)
    }

    private fun applyInteractivePhysicsState() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || !isGlassInteractive || overlay?.isActive == true || morphActive) {
            return
        }
        val xf = scratchControlTransform
        pressPhysics.evaluateInto(w, h, density, xf)
        setOpticalPressure(xf.press, xf.stretch)
        if (pivotX != w * 0.5f) pivotX = w * 0.5f
        if (pivotY != h * 0.5f) pivotY = h * 0.5f
        if (scaleX != xf.scaleX) scaleX = xf.scaleX
        if (scaleY != xf.scaleY) scaleY = xf.scaleY
        if (xf.shiftX != appliedPullTx) {
            translationX = (translationX - appliedPullTx) + xf.shiftX
            appliedPullTx = xf.shiftX
        }
        if (xf.shiftY != appliedPullTy) {
            translationY = (translationY - appliedPullTy) + xf.shiftY
            appliedPullTy = xf.shiftY
        }
        interactiveTransformApplied = abs(xf.scaleX - 1f) > 0.0005f ||
            abs(xf.scaleY - 1f) > 0.0005f ||
            abs(appliedPullTx) > 0.05f ||
            abs(appliedPullTy) > 0.05f
    }

    private fun clearInteractiveTransform() {
        if (!interactiveTransformApplied && appliedPullTx == 0f && appliedPullTy == 0f) return
        interactiveTransformApplied = false
        scaleX = 1f
        scaleY = 1f
        if (appliedPullTx != 0f) {
            translationX -= appliedPullTx
            appliedPullTx = 0f
        }
        if (appliedPullTy != 0f) {
            translationY -= appliedPullTy
            appliedPullTy = 0f
        }
    }

    private fun setOpticalPressure(press: Float, stretch: Float) {
        if (this.press == press && this.stretch == stretch) return
        this.press = press
        this.stretch = stretch
        invalidateOptics()
    }
}
