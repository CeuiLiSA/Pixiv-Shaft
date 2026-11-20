package ceui.pixiv.witstudio.widget.internal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RecordingCanvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.abs

@RequiresApi(31)
internal class WitLiquidGlassBackdrop {
    internal val node = RenderNode("WitLiquidGlassBackdrop")

    internal fun begin(width: Int, height: Int): RecordingCanvas {
        node.setPosition(0, 0, width, height)
        return node.beginRecording()
    }

    internal fun end() {
        node.endRecording()
    }

    internal fun discard() {
        node.discardDisplayList()
    }
}

/** One padded, control-sized input for HWUI's image filters. Multiple controls share the backdrop. */
@RequiresApi(31)
internal open class WitLiquidGlassRenderer {
    internal open val backend: WitLiquidGlassBackend = WitLiquidGlassBackend.BACKDROP
    protected val patch: RenderNode = RenderNode("WitLiquidGlassPatch")
    protected var inset: Int = 0
        private set
    private val localToBackdrop = Matrix()
    private val backdropToLocal = Matrix()
    private val previousTransform = Matrix()
    private var previousBackdrop: WitLiquidGlassBackdrop? = null
    private var blurRadius = -1f

    internal val hasBackdrop: Boolean get() = previousBackdrop != null

    internal fun updateBackdrop(view: View, backdrop: WitLiquidGlassBackdrop, scrollX: Int, scrollY: Int, density: Float) {
        localToBackdrop.setTranslate((view.left - scrollX).toFloat(), (view.top - scrollY).toFloat())
        localToBackdrop.preConcat(view.matrix)
        if (!localToBackdrop.invert(backdropToLocal)) {
            clearBackdrop()
            return
        }

        // Covers the maximum bezel displacement and the blur kernel at the patch boundary.
        val newInset = WitLiquidGlassBudget.inset(density)
        val paddingChanged = inset != newInset
        inset = newInset
        val resized = patch.setPosition(0, 0, view.width + 2 * inset, view.height + 2 * inset)
        if (!resized && !paddingChanged && previousBackdrop === backdrop && previousTransform == backdropToLocal) return

        val recording = patch.beginRecording()
        try {
            recording.translate(inset.toFloat(), inset.toFloat())
            recording.concat(backdropToLocal)
            recording.drawRenderNode(backdrop.node)
        } finally {
            patch.endRecording()
        }
        previousBackdrop = backdrop
        previousTransform.set(backdropToLocal)
    }

    internal fun clearBackdrop(): Boolean {
        if (previousBackdrop == null) return false
        previousBackdrop = null
        patch.discardDisplayList()
        return true
    }

    internal open fun updateOptics(
        width: Int, height: Int, radius: Float, density: Float, dark: Boolean, clear: Boolean,
        tint: Int, clearDimming: Float, touchX: Float, touchY: Float, press: Float, stretch: Float,
    ) {
        val radiusPx = density * if (clear) 1.5f else 4.5f
        if (blurRadius != radiusPx) {
            patch.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            blurRadius = radiusPx
        }
    }

    internal open fun draw(
        canvas: Canvas, material: WitLiquidGlassPaint, touchX: Float, touchY: Float, press: Float, stretch: Float,
    ) {
        material.updateDeformation(touchX, touchY, stretch)
        canvas.withClip(material.blurClip()) { drawPatch(this) }
        material.drawBlurOverlay(canvas, press)
    }

    protected fun drawPatch(canvas: Canvas) {
        canvas.withTranslation(-inset.toFloat(), -inset.toFloat()) { drawRenderNode(patch) }
    }
}

/** Shared rounded clips and a legible surface for canvases without the refraction shader. */
internal class WitLiquidGlassPaint {
    private val clip = Path()
    private val bounds = RectF()
    private val deformedBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightMatrix = Matrix()
    private val light = RadialGradient(0f, 0f, 1f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private var radius = 0f
    private var deformedRadius = 0f
    private var density = 1f
    private var lightX = Float.NaN
    private var lightY = Float.NaN
    private var lightRadius = 1f
    private var rimWidth = 0f
    private var fallbackFill = Color.TRANSPARENT
    private var blurWash = Color.TRANSPARENT
    private var rim: Shader? = null

    internal fun update(
        width: Int, height: Int, radius: Float, density: Float, base: Int, tint: Int,
        dark: Boolean, clear: Boolean, clearDimming: Float,
    ) {
        this.radius = radius
        this.density = density
        rimWidth = maxOf(1f, density * 0.55f)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        lightRadius = maxOf(1f, minOf(width, height) * 0.80f)
        lightX = Float.NaN

        val tintedBase = ColorUtils.blendARGB(base, tint or Color.BLACK, Color.alpha(tint) / 765f)
        val translucentFill = ColorUtils.setAlphaComponent(tintedBase, if (clear) 204 else 242)
        fallbackFill = if (clear) {
            ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(Color.BLACK, (clearDimming * 255f).toInt()),
                translucentFill,
            )
        } else translucentFill
        blurWash = if (clear) {
            ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(Color.BLACK, (clearDimming * 255f).toInt()),
                ColorUtils.compositeColors(tint, 0x10FFFFFF),
            )
        } else {
            ColorUtils.setAlphaComponent(tintedBase, if (dark) 118 else 108)
        }
        rim = if (width > 0 && height > 0) {
            LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(if (dark) 0x64FFFFFF else 0xBBFFFFFF.toInt(), 0x1FFFFFFF, 0x59000000, 0x73FFFFFF),
                floatArrayOf(0f, 0.35f, 0.66f, 1f), Shader.TileMode.CLAMP,
            )
        } else null
    }

    internal fun updateDeformation(touchX: Float, touchY: Float, stretch: Float) {
        val width = bounds.width()
        val height = bounds.height()
        val elastic = stretch * minOf(density, minOf(width, height) / 48f)
        val gripX = (touchX / maxOf(1f, width) * 2f - 1f).coerceIn(-1f, 1f)
        val gripY = (touchY / maxOf(1f, height) * 2f - 1f).coerceIn(-1f, 1f)
        val expandX = elastic * (1.6f + abs(gripX) * 0.55f)
        val expandY = elastic * (1.6f + abs(gripY) * 0.55f)
        deformedBounds.set(-expandX, -expandY, width + expandX, height + expandY)
        deformedBounds.offset(gripX * elastic * 1.8f, gripY * elastic * 1.4f)
        val maxRadius = (minOf(deformedBounds.width(), deformedBounds.height()) / 2f).coerceAtLeast(0f)
        deformedRadius = (radius + elastic * 1.6f).coerceIn(0f, maxRadius)

        if (touchX != lightX || touchY != lightY) {
            lightX = touchX
            lightY = touchY
            lightMatrix.setScale(lightRadius, lightRadius)
            lightMatrix.postTranslate(touchX, touchY)
            light.setLocalMatrix(lightMatrix)
        }
    }

    internal fun blurClip(): Path {
        clip.rewind()
        clip.addRoundRect(deformedBounds, deformedRadius, deformedRadius, Path.Direction.CW)
        return clip
    }

    internal fun drawFallback(canvas: Canvas, touchX: Float, touchY: Float, press: Float, stretch: Float) {
        updateDeformation(touchX, touchY, stretch)
        drawSurface(canvas, fallbackFill, press)
    }

    internal fun drawBlurOverlay(canvas: Canvas, press: Float) {
        drawSurface(canvas, blurWash, press)
    }

    private fun drawSurface(canvas: Canvas, fill: Int, press: Float) {
        if (deformedBounds.width() <= 0f || deformedBounds.height() <= 0f) return
        paint.color = fill
        canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
        if (press > 0f) {
            paint.shader = light
            paint.alpha = (press * 180f).toInt()
            canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
        }
        val rimShader = rim
        if (rimShader != null && deformedBounds.width() > rimWidth && deformedBounds.height() > rimWidth) {
            val halfStroke = rimWidth / 2f
            val rimRadius = (deformedRadius - halfStroke).coerceAtLeast(0f)
            paint.color = Color.WHITE
            paint.shader = rimShader
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rimWidth
            canvas.drawRoundRect(
                deformedBounds.left + halfStroke, deformedBounds.top + halfStroke,
                deformedBounds.right - halfStroke, deformedBounds.bottom - halfStroke,
                rimRadius, rimRadius, paint,
            )
            paint.style = Paint.Style.FILL
        }
        paint.shader = null
    }
}
