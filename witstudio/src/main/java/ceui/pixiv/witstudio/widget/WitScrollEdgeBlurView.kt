package ceui.pixiv.witstudio.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackdrop
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBudget
import ceui.pixiv.witstudio.widget.internal.WitScrollEdgeCurve
import ceui.pixiv.witstudio.widget.internal.WitScrollEdgeRenderer
import ceui.pixiv.witstudio.widget.internal.WitScrollEdgeStandardRenderer
import ceui.pixiv.witstudio.widget.internal.WitScrollEdgeUltraRenderer
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pixel-calibrated iOS 26 `UIScrollEdgeEffect` (`.soft` style) progressive edge blur view.
 *
 * Add as a direct sibling above the scrolling content child inside a [WitLiquidGlassLayout] or
 * [WitLiquidGlassCoordinatorLayout], and place floating navigation chrome (title bar, buttons,
 * tabs) above this view so foreground chrome stays razor-sharp while scrolling content melts
 * smoothly into progressive Gaussian blur and a borderless atmospheric veil.
 *
 * Rendering tiers (automatically synced with [WitLiquidGlassQuality]):
 * - [WitLiquidGlassQuality.ULTRA] (API 33+): 5-level hardware Gaussian pyramid + AGSL per-pixel
 *   continuous variable-radius progressive blur, anchor-protected soft-shoulder falloff, vibrancy
 *   preservation, per-pixel luminance-adaptive veil clamping, and IGN OLED anti-banding dithering.
 * - [WitLiquidGlassQuality.STANDARD] (API 31+): lightweight downscaled HWUI Gaussian blur masked
 *   by a smooth multi-stop vertical alpha curve + soft veil.
 * - [WitLiquidGlassQuality.NONE] / API < 31 / low-RAM / software canvas: zero-GPU smooth eased
 *   gradient fallback preserving floating header legibility without any hard divider line.
 */
public class WitScrollEdgeBlurView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    public enum class Edge {
        TOP,
        BOTTOM,
    }

    public var edge: Edge = Edge.TOP
        set(value) {
            if (field == value) return
            field = value
            invalidateOptics()
            (parent as? View)?.invalidate()
        }

    /**
     * Protected chrome height (px) from the active edge (for example `statusBarHeight` for
     * status-bar-only intrusion, or `statusBarHeight + headerBarHeight` for a floating top bar).
     * When `layout_height="wrap_content"`, this view automatically measures to
     * `anchorHeightPx + ceil(featherHeightDp * density)`.
     */
    public var anchorHeightPx: Int = 0
        set(value) {
            val clamped = value.coerceAtLeast(0)
            if (field == clamped) return
            field = clamped
            invalidateOptics()
            requestLayout()
        }

    /**
     * Optional animated visible anchor height (px) inside the already laid-out bounds
     * (`< 0` follows [anchorHeightPx]). Updating this during scroll-driven chrome collapse or
     * expansion adjusts the optical blur & veil span with **zero** `requestLayout()` passes and
     * **zero** RenderNode FBO reallocations.
     */
    public var visualAnchorHeightPx: Int = -1
        set(value) {
            val normalized = if (value < 0) -1 else value
            if (field == normalized) return
            field = normalized
            invalidateOptics()
        }

    /** Soft melt-out tail length (dp) below [anchorHeightPx] where blur & veil dissolve to zero. */
    public var featherHeightDp: Float = WitScrollEdgeCurve.DEFAULT_FEATHER_DP
        set(value) {
            val clamped = value.coerceIn(4f, 120f)
            if (field == clamped) return
            field = clamped
            invalidateOptics()
            requestLayout()
        }

    /** Peak Gaussian blur radius (dp) reached at the screen edge in [WitLiquidGlassQuality.ULTRA]. */
    public var maxBlurRadiusDp: Float = WitScrollEdgeCurve.DEFAULT_MAX_BLUR_DP
        set(value) {
            val clamped = value.coerceIn(WitScrollEdgeCurve.MIN_BLUR_DP, WitScrollEdgeCurve.MAX_BLUR_DP)
            if (field == clamped) return
            field = clamped
            invalidateOptics()
            (parent as? View)?.invalidate()
        }

    /**
     * Optional explicit surface color blended by the soft veil (`null` resolves `wit_bg` for the
     * current theme polarity).
     */
    @get:ColorInt
    @setparam:ColorInt
    public var scrimColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateOptics()
        }

    /** Overall strength multiplier (`0..1`) applied to the soft surface veil. */
    public var scrimStrength: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidateOptics()
        }

    public var tone: WitLiquidGlassView.Tone = WitLiquidGlassView.Tone.THEME
        set(value) {
            if (field == value) return
            field = value
            invalidateOptics()
        }

    /**
     * Explicit rendering quality override; `null` inherits the parent [WitLiquidGlassHost.quality]
     * or [WitLiquidGlassQuality.defaultQuality].
     */
    public var quality: WitLiquidGlassQuality? = null
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT >= 31) {
                discardRenderer()
            }
            invalidateOptics()
            (parent as? View)?.invalidate()
        }

    /**
     * When live blur is inactive ([WitLiquidGlassQuality.NONE], API < 31, low RAM, or software
     * canvas), `true` keeps the fallback gradient near-opaque across [anchorHeightPx] so floating
     * header text remains crisp over unblurred scrolling content; `false` renders a lighter
     * translucent status-bar scrim.
     */
    public var isOpaqueHeaderFallback: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidateOptics()
        }

    public val effectiveQuality: WitLiquidGlassQuality
        get() = quality ?: (parent as? WitLiquidGlassHost)?.quality ?: WitLiquidGlassQuality.defaultQuality

    private var palette = V3Palette.from(context)
    private val isDark: Boolean
        get() = when (tone) {
            WitLiquidGlassView.Tone.THEME -> palette.isDark
            WitLiquidGlassView.Tone.LIGHT -> false
            WitLiquidGlassView.Tone.DARK -> true
        }
    private val density: Float get() = resources.displayMetrics.density
    private val featherHeightPx: Float get() = ceil(featherHeightDp * density)

    private var renderer: WitScrollEdgeRenderer? = null
    private var lastCanvasEdge: Int = 4096
    private var liveOpticsDirty: Boolean = true
    private var fallbackOpticsDirty: Boolean = true

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fallbackShader: LinearGradient? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        context.withStyledAttributes(attrs, R.styleable.WitScrollEdgeBlurView, defStyleAttr) {
            edge = Edge.entries.getOrElse(getInt(R.styleable.WitScrollEdgeBlurView_wit_scrollEdge, 0)) { Edge.TOP }
            anchorHeightPx = getDimensionPixelSize(R.styleable.WitScrollEdgeBlurView_wit_edgeAnchorHeight, 0)
            featherHeightDp = getDimension(
                R.styleable.WitScrollEdgeBlurView_wit_edgeFeatherHeight,
                WitScrollEdgeCurve.DEFAULT_FEATHER_DP * density,
            ) / density
            maxBlurRadiusDp = getDimension(
                R.styleable.WitScrollEdgeBlurView_wit_edgeMaxBlurRadius,
                WitScrollEdgeCurve.DEFAULT_MAX_BLUR_DP * density,
            ) / density
            if (hasValue(R.styleable.WitScrollEdgeBlurView_wit_edgeScrimColor)) {
                scrimColor = getColor(R.styleable.WitScrollEdgeBlurView_wit_edgeScrimColor, Color.TRANSPARENT)
            }
            scrimStrength = getFloat(R.styleable.WitScrollEdgeBlurView_wit_edgeScrimStrength, 1f)
            isOpaqueHeaderFallback = getBoolean(
                R.styleable.WitScrollEdgeBlurView_wit_edgeOpaqueHeaderFallback,
                true,
            )
            tone = WitLiquidGlassView.Tone.entries.getOrElse(
                getInt(R.styleable.WitScrollEdgeBlurView_wit_glassTone, 0),
            ) { WitLiquidGlassView.Tone.THEME }
            val qIdx = getInt(R.styleable.WitScrollEdgeBlurView_wit_glassQuality, -1)
            if (qIdx >= 0) {
                quality = WitLiquidGlassQuality.fromOrdinal(qIdx)
            }
        }
    }

    /** Convenience helper for setting [anchorHeightPx] in dp. */
    public fun setAnchorHeightDp(dp: Float) {
        anchorHeightPx = ceil(dp.coerceAtLeast(0f) * density).toInt()
    }

    /**
     * Smoothly drives scroll-edge activation (`0f` = at rest at top of scroll with zero haze on
     * first card and zero backdrop GPU work; `1f` = fully active while content scrolls beneath
     * floating chrome), waking up the parent [WitLiquidGlassHost] on `0 <-> >0` transitions.
     */
    public fun setActivationProgress(progress: Float) {
        val target = progress.coerceIn(0f, 1f)
        val wasZero = alpha <= 0f
        if (abs(alpha - target) > 0.004f || (wasZero != (target <= 0f))) {
            alpha = target
            if (wasZero != (target <= 0f)) {
                (parent as? View)?.invalidate()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (anchorHeightPx + featherHeightPx.toInt()).coerceAtLeast(0)
        val measuredW = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredH = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOptics()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        palette = V3Palette.from(context)
        invalidateOptics()
    }

    internal fun planPatch(canvasEdge: Int): WitLiquidGlassBudget.PatchPlan {
        lastCanvasEdge = canvasEdge
        val q = effectiveQuality
        if (!q.isLiveEnabled || width <= 0 || height <= 0) {
            return WitLiquidGlassBudget.PatchPlan(0L, 1f)
        }
        val ultra = q == WitLiquidGlassQuality.ULTRA && Build.VERSION.SDK_INT >= 33 &&
            WitLiquidGlassBackend.REFRACTION.available
        val plan = WitScrollEdgeCurve.planPyramid(
            width = width,
            height = height,
            density = density,
            canvasEdge = canvasEdge,
            ultra = ultra,
            maxBlurDp = maxBlurRadiusDp,
        )
        return WitLiquidGlassBudget.PatchPlan(plan.totalPixels, 1f)
    }

    internal fun onHostQualityChanged() {
        if (quality != null) return
        if (Build.VERSION.SDK_INT >= 31) {
            discardRenderer()
        }
        invalidateOptics()
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
        val wantUltra = effectiveQuality == WitLiquidGlassQuality.ULTRA && Build.VERSION.SDK_INT >= 33 &&
            WitLiquidGlassBackend.REFRACTION.available
        if (renderer != null && (renderer is WitScrollEdgeUltraRenderer) != wantUltra) {
            discardRenderer()
        }
        val current = renderer ?: createRenderer(wantUltra)?.also {
            renderer = it
            liveOpticsDirty = true
        } ?: return

        val hadBackdrop = current.hasBackdrop
        val changed = WitLiquidGlassBackend.BACKDROP.attempt {
            current.updateBackdrop(
                view = this,
                backdrop = backdrop,
                parentScrollX = parentScrollX,
                parentScrollY = parentScrollY,
                density = density,
                canvasEdge = lastCanvasEdge,
                maxBlurDp = maxBlurRadiusDp,
                isTopEdge = edge == Edge.TOP,
            )
        }
        if (changed == null) {
            discardRenderer()
        } else if (changed) {
            invalidateOptics()
        } else if (hadBackdrop != current.hasBackdrop) {
            invalidate()
        }
    }

    @RequiresApi(31)
    private fun createRenderer(ultra: Boolean): WitScrollEdgeRenderer? {
        if (ultra && Build.VERSION.SDK_INT >= 33) {
            WitLiquidGlassBackend.REFRACTION.attempt { WitScrollEdgeUltraRenderer() }?.let { return it }
        }
        return WitLiquidGlassBackend.BACKDROP.attempt { WitScrollEdgeStandardRenderer() }
    }

    @RequiresApi(31)
    private fun discardRenderer() {
        val current = renderer ?: return
        renderer = null
        if (current.clearBackdrop()) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val resolvedScrim = resolveScrimColor()
        val dark = isDark
        val isTop = edge == Edge.TOP
        val effectiveFeather = featherHeightPx.coerceIn(1f, h.toFloat())
        val rawAnchor = if (visualAnchorHeightPx >= 0) visualAnchorHeightPx else anchorHeightPx
        val effectiveAnchor = rawAnchor.toFloat().coerceIn(0f, (h.toFloat() - effectiveFeather).coerceAtLeast(0f))

        if (Build.VERSION.SDK_INT >= 31 && isHardwareAccelerated && canvas.isHardwareAccelerated &&
            effectiveQuality.isLiveEnabled
        ) {
            val current = renderer
            if (current != null && current.hasBackdrop) {
                val drawn = current.backend.attempt {
                    if (liveOpticsDirty) {
                        current.updateOptics(
                            width = w,
                            height = h,
                            anchorHeightPx = effectiveAnchor,
                            featherHeightPx = effectiveFeather,
                            density = density,
                            isDark = dark,
                            isTopEdge = isTop,
                            scrimColor = resolvedScrim,
                            scrimStrength = scrimStrength,
                        )
                        liveOpticsDirty = false
                    }
                    current.draw(canvas, w, h)
                }
                if (drawn != null) return
                discardRenderer()
            }
        }

        if (fallbackOpticsDirty || fallbackShader == null) {
            rebuildFallbackShader(h, effectiveAnchor, effectiveFeather, dark, isTop, resolvedScrim)
            fallbackOpticsDirty = false
        }
        val shader = fallbackShader ?: return
        fallbackPaint.shader = shader
        val spanH = (effectiveAnchor + effectiveFeather).coerceIn(0f, h.toFloat())
        if (isTop) {
            canvas.drawRect(0f, 0f, w.toFloat(), spanH, fallbackPaint)
        } else {
            canvas.drawRect(0f, h.toFloat() - spanH, w.toFloat(), h.toFloat(), fallbackPaint)
        }
        fallbackPaint.shader = null
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            if (Build.VERSION.SDK_INT >= 31) renderer?.clearBackdrop()
        } else {
            invalidateOptics()
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) discardRenderer()
        fallbackShader = null
        super.onDetachedFromWindow()
    }

    @ColorInt
    private fun resolveScrimColor(): Int {
        scrimColor?.let { return it or Color.BLACK }
        return when (tone) {
            WitLiquidGlassView.Tone.THEME -> ContextCompat.getColor(context, R.color.wit_bg)
            WitLiquidGlassView.Tone.LIGHT -> 0xFFF8F7FC.toInt()
            WitLiquidGlassView.Tone.DARK -> 0xFF101014.toInt()
        }
    }

    private fun rebuildFallbackShader(
        height: Int,
        anchorH: Float,
        featherH: Float,
        dark: Boolean,
        isTop: Boolean,
        @ColorInt scrim: Int,
    ) {
        if (height <= 0) {
            fallbackShader = null
            return
        }
        val (pos, alphas) = WitScrollEdgeCurve.buildFallbackAlphaStops(
            anchorH = anchorH,
            featherH = featherH,
            isDark = dark,
            opaqueHeaderFallback = isOpaqueHeaderFallback,
            scrimStrength = scrimStrength,
        )
        val opaque = scrim or Color.BLACK
        val colors = IntArray(alphas.size) { i -> ColorUtils.setAlphaComponent(opaque, alphas[i]) }
        val spanH = (anchorH + featherH).coerceIn(1f, height.toFloat())
        val y0 = if (isTop) 0f else height.toFloat()
        val y1 = if (isTop) spanH else height.toFloat() - spanH
        fallbackShader = LinearGradient(0f, y0, 0f, y1, colors, pos, Shader.TileMode.CLAMP)
    }

    private fun invalidateOptics() {
        liveOpticsDirty = true
        fallbackOpticsDirty = true
        fallbackShader = null
        invalidate()
    }

    public companion object {
        private const val DARK_HALO_COLOR: Int = 0x59000000 // Subtle 35% black cushion
        private const val LIGHT_HALO_COLOR: Int = 0x8CFFFFFF.toInt() // Subtle 55% white cushion

        /**
         * Applies iOS 26 floating-chrome legibility styling to a [TextView] (e.g. floating toolbar
         * title) positioned over [WitScrollEdgeBlurView]: a subtle theme-polarized optical halo
         * that is invisible against matching page ground yet lifts glyphs cleanly off opposite-polarity
         * scrolling artwork. Pass `active = false` to clear the shadow cleanly.
         */
        @JvmStatic
        public fun applyFloatingTextLegibility(textView: TextView, isDark: Boolean, active: Boolean) {
            if (!active) {
                textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                return
            }
            val d = textView.resources.displayMetrics.density
            val halo = if (isDark) DARK_HALO_COLOR else LIGHT_HALO_COLOR
            textView.setShadowLayer(3f * d, 0f, 0.5f * d, halo)
        }
    }
}
