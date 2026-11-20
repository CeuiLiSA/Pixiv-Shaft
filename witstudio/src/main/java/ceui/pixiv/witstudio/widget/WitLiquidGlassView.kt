package ceui.pixiv.witstudio.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.withTranslation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackend
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassBackdrop
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassPaint
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassRenderer
import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassShader
import kotlin.math.abs

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

    public var material: Material = Material.REGULAR
        set(value) {
            if (field == value) return
            field = value
            invalidateMaterial()
        }

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

    private val materialPaint = WitLiquidGlassPaint()
    private var renderer: WitLiquidGlassRenderer? = null
    private var materialDirty = true
    private var opticsDirty = true

    private var touchPointer = MotionEvent.INVALID_POINTER_ID
    private var touchX = 0f
    private var touchY = 0f
    private var press = 0f
    private var stretch = 0f
    private var pressTarget = 0f
    private var pressSpring: SpringAnimation? = null

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, radius)
                outline.alpha = if (isDark) 0.32f else 0.78f
            }
        }

        context.withStyledAttributes(attrs, intArrayOf(android.R.attr.elevation), defStyleAttr) {
            if (!hasValue(0)) elevation = 9f * density
        }

        context.withStyledAttributes(attrs, R.styleable.WitLiquidGlassView, defStyleAttr) {
            material = Material.entries.getOrElse(getInt(R.styleable.WitLiquidGlassView_wit_glassMaterial, 0)) {
                Material.REGULAR
            }
            tone = Tone.entries.getOrElse(getInt(R.styleable.WitLiquidGlassView_wit_glassTone, 0)) { Tone.THEME }
            cornerRadiusDp = getDimension(
                R.styleable.WitLiquidGlassView_wit_glassCornerRadius, Float.POSITIVE_INFINITY,
            ) / density
            tintColor = getColor(R.styleable.WitLiquidGlassView_wit_glassTint, Color.TRANSPARENT)
            clearDimming = getFloat(R.styleable.WitLiquidGlassView_wit_glassClearDimming, 0f)
            isGlassInteractive = getBoolean(R.styleable.WitLiquidGlassView_wit_glassInteractive, false)
        }
    }

    @RequiresApi(31)
    internal fun updateBackdrop(backdrop: WitLiquidGlassBackdrop?, parentScrollX: Int, parentScrollY: Int) {
        if (backdrop == null || !WitLiquidGlassBackend.BACKDROP.available) {
            discardRenderer()
            return
        }
        if (renderer?.backend?.available == false) discardRenderer()
        val current = renderer ?: createRenderer()?.also {
            renderer = it
            opticsDirty = true
        } ?: return
        val hasBackdrop = current.hasBackdrop
        val prepared = WitLiquidGlassBackend.BACKDROP.attempt {
            current.updateBackdrop(this, backdrop, parentScrollX, parentScrollY, density)
        }
        if (prepared == null) discardRenderer()
        else if (hasBackdrop != current.hasBackdrop) invalidate()
    }

    @RequiresApi(31)
    private fun createRenderer(): WitLiquidGlassRenderer? {
        if (Build.VERSION.SDK_INT >= 33) {
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
        baseFill = if (dark == palette.isDark) palette.cardFill else V3Palette(palette.primary, dark).cardFill
        invalidateMaterial()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val scale = density
        val dark = isDark
        val clear = material == Material.CLEAR
        if (materialDirty) {
            materialPaint.update(w, h, radius, scale, baseFill, tintColor, dark, clear, clearDimming)
            materialDirty = false
        }
        canvas.withTranslation(scrollX.toFloat(), scrollY.toFloat()) {
            if (Build.VERSION.SDK_INT >= 31 && isHardwareAccelerated) {
                val current = renderer
                if (current != null && current.hasBackdrop) {
                    val drawn = current.backend.attempt {
                        if (opticsDirty) {
                            current.updateOptics(
                                w, h, radius, scale, dark, clear, tintColor, clearDimming,
                                touchX, touchY, press, stretch,
                            )
                            opticsDirty = false
                        }
                        current.draw(this, materialPaint, touchX, touchY, press, stretch)
                    }
                    if (drawn != null) return@withTranslation
                    discardRenderer()
                }
            }
            materialPaint.drawFallback(this, touchX, touchY, press, stretch)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (!isGlassInteractive || !isEnabled) return handled
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (handled) {
                touchPointer = event.getPointerId(0)
                updateTouch(event.x, event.y)
                animatePress(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(touchPointer)
                if (index >= 0) {
                    updateTouch(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == touchPointer) releaseTouch()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseTouch()
        }
        return handled
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isGlassInteractive && !isEnabled) {
            resetPress()
        } else if (isGlassInteractive && touchPointer == MotionEvent.INVALID_POINTER_ID) {
            if (isPressed) updateTouch(width / 2f, height / 2f)
            animatePress(isPressed)
        }
    }

    override fun onDetachedFromWindow() {
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

    private fun releaseTouch() {
        touchPointer = MotionEvent.INVALID_POINTER_ID
        animatePress(false)
    }

    private fun resetPress() {
        pressSpring?.cancel()
        touchPointer = MotionEvent.INVALID_POINTER_ID
        pressTarget = 0f
        setPressure(0f, 0f)
    }

    private fun animatePress(pressed: Boolean) {
        val target = if (pressed) 1f else 0f
        if (pressTarget == target) return
        pressTarget = target
        if (!motionEnabled()) {
            pressSpring?.cancel()
            setPressure(target, 0f)
            return
        }
        val spring = pressSpring ?: SpringAnimation(FloatValueHolder(), 0f).apply {
            this.spring.stiffness = 620f
            setMinimumVisibleChange(0.005f)
            addUpdateListener { _, value, _ ->
                setPressure(value.coerceIn(0f, 1f), value)
            }
        }.also { pressSpring = it }
        if (!spring.isRunning) {
            if (pressed && stretch == 0f) setPressure(0.12f, 0.12f)
            spring.setStartValue(stretch)
        }
        spring.spring.dampingRatio = if (pressed) 0.84f else 0.62f
        spring.animateToFinalPosition(target)
    }

    private fun setPressure(press: Float, stretch: Float) {
        if (this.press == press && this.stretch == stretch) return
        this.press = press
        this.stretch = stretch
        invalidateOptics()
    }
}
