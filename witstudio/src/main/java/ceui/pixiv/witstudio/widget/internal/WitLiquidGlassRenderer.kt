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
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class WitLiquidGlassMorphTail(
    var tipX: Float = 0f,
    var tipY: Float = 0f,
    var tipRadius: Float = 0f,
    var bellyX: Float = 0f,
    var bellyY: Float = 0f,
    var bellyRadius: Float = 0f,
    var blendPx: Float = 0f,
) {
    val active: Boolean get() = blendPx > 0.01f && tipRadius > 0.5f && bellyRadius > 0.5f

    fun clear() {
        tipX = 0f
        tipY = 0f
        tipRadius = 0f
        bellyX = 0f
        bellyY = 0f
        bellyRadius = 0f
        blendPx = 0f
    }
}

/**
 * Builds the closed teardrop-union silhouette (Body B rounded rect unioned with the tangent
 * uneven capsule connecting anchor tip to Body B's belly). Used for child clipping and non-AGSL
 * glass backends so every API level shares the same liquid droplet contour.
 */
internal fun buildLiquidGlassMorphPath(
    outPath: Path,
    scratchPath: Path,
    arcRect: RectF,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadius: Float,
    tail: WitLiquidGlassMorphTail?,
) {
    outPath.rewind()
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    if (w <= 0f || h <= 0f) return
    val rad = cornerRadius.coerceIn(0f, minOf(w, h) * 0.5f)
    outPath.addRoundRect(left, top, right, bottom, rad, rad, Path.Direction.CW)
    if (tail == null || !tail.active) return

    val dx = tail.bellyX - tail.tipX
    val dy = tail.bellyY - tail.tipY
    val dist = hypot(dx, dy)
    val r1 = tail.tipRadius
    val r2 = tail.bellyRadius
    if (r1 <= 0.5f && r2 <= 0.5f) return

    scratchPath.rewind()
    if (dist <= 0.5f || dist <= abs(r1 - r2) + 0.5f) {
        if (r1 > 0.5f) {
            scratchPath.addCircle(tail.tipX, tail.tipY, r1, Path.Direction.CW)
        }
        if (r2 > 0.5f) {
            scratchPath.addCircle(tail.bellyX, tail.bellyY, r2, Path.Direction.CW)
        }
    } else {
        val theta = atan2(dy, dx)
        val beta = acos(((r1 - r2) / dist).coerceIn(-0.999f, 0.999f))
        val a1Start = theta - beta
        val a1End = theta + beta
        val sweep1Deg = ((2.0 * PI - 2.0 * beta) * 180.0 / PI).toFloat()
        val start1Deg = (a1End * 180.0 / PI).toFloat()
        val sweep2Deg = (2.0 * beta * 180.0 / PI).toFloat()
        val start2Deg = (a1Start * 180.0 / PI).toFloat()

        scratchPath.moveTo(
            tail.tipX + r1 * cos(a1End).toFloat(),
            tail.tipY + r1 * sin(a1End).toFloat(),
        )
        arcRect.set(tail.tipX - r1, tail.tipY - r1, tail.tipX + r1, tail.tipY + r1)
        scratchPath.arcTo(arcRect, start1Deg, sweep1Deg, false)
        scratchPath.lineTo(
            tail.bellyX + r2 * cos(a1Start).toFloat(),
            tail.bellyY + r2 * sin(a1Start).toFloat(),
        )
        arcRect.set(tail.bellyX - r2, tail.bellyY - r2, tail.bellyX + r2, tail.bellyY + r2)
        scratchPath.arcTo(arcRect, start2Deg, sweep2Deg, false)
        scratchPath.close()
    }

    val opSuccess = try {
        outPath.op(scratchPath, Path.Op.UNION)
    } catch (_: Throwable) {
        false
    }
    if (!opSuccess || outPath.isEmpty) {
        outPath.rewind()
        outPath.addRoundRect(left, top, right, bottom, rad, rad, Path.Direction.CW)
        outPath.addPath(scratchPath)
    }
}

@RequiresApi(31)
internal class WitLiquidGlassBackdrop {
    internal val node = RenderNode("WitLiquidGlassBackdrop")
    internal val hasDisplayList: Boolean get() = node.hasDisplayList()

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
    protected var renderScale: Float = 1f
        private set
    private val localToBackdrop = Matrix()
    private val backdropToLocal = Matrix()
    private val previousTransform = Matrix()
    private var previousBackdrop: WitLiquidGlassBackdrop? = null
    private var previousScale: Float = -1f
    private var blurRadius = -1f

    internal val hasBackdrop: Boolean get() = previousBackdrop != null && patch.hasDisplayList()

    internal fun updateBackdrop(
        view: View,
        backdrop: WitLiquidGlassBackdrop,
        scrollX: Int,
        scrollY: Int,
        density: Float,
        scale: Float = 1f,
    ): Boolean {
        localToBackdrop.setTranslate((view.left - scrollX).toFloat(), (view.top - scrollY).toFloat())
        localToBackdrop.preConcat(view.matrix)
        if (!localToBackdrop.invert(backdropToLocal)) {
            clearBackdrop()
            return false
        }

        // Covers the maximum bezel displacement and the blur kernel at the patch boundary.
        val effectiveScale = scale.coerceIn(0.5f, 1f)
        val newInset = WitLiquidGlassBudget.inset(density * effectiveScale)
        val paddingChanged = inset != newInset
        val scaleChanged = renderScale != effectiveScale
        inset = newInset
        renderScale = effectiveScale
        val scaledWidth = ceil(view.width * effectiveScale).toInt().coerceAtLeast(1)
        val scaledHeight = ceil(view.height * effectiveScale).toInt().coerceAtLeast(1)
        val resized = patch.setPosition(0, 0, scaledWidth + 2 * inset, scaledHeight + 2 * inset)
        val missingDisplayList = !patch.hasDisplayList()
        val backdropChanged = previousBackdrop !== backdrop
        if (!resized && !paddingChanged && !scaleChanged && !missingDisplayList &&
            !backdropChanged && previousScale == effectiveScale && previousTransform == backdropToLocal
        ) {
            return false
        }

        val recording = patch.beginRecording()
        try {
            recording.translate(inset.toFloat(), inset.toFloat())
            if (effectiveScale != 1f) {
                recording.scale(effectiveScale, effectiveScale)
            }
            recording.concat(backdropToLocal)
            recording.drawRenderNode(backdrop.node)
        } finally {
            patch.endRecording()
        }
        previousBackdrop = backdrop
        previousScale = effectiveScale
        previousTransform.set(backdropToLocal)
        return paddingChanged || scaleChanged || missingDisplayList || backdropChanged
    }

    internal fun clearBackdrop(): Boolean {
        val hadBackdrop = previousBackdrop != null
        previousBackdrop = null
        previousScale = -1f
        onBackdropCleared()
        patch.discardDisplayList()
        return hadBackdrop
    }

    protected open fun onBackdropCleared() {
        blurRadius = -1f
    }

    internal open fun updateOptics(
        left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, density: Float, dark: Boolean, clear: Boolean, largeSurface: Boolean,
        tint: Int, clearDimming: Float, touchX: Float, touchY: Float, press: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail?,
    ) {
        val radiusPx = density * renderScale * when {
            clear -> 1.5f
            largeSurface -> 6.5f
            else -> 4.5f
        }
        if (blurRadius != radiusPx) {
            patch.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            blurRadius = radiusPx
        }
    }

    internal open fun draw(
        canvas: Canvas,
        material: WitLiquidGlassPaint,
        left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float,
        touchX: Float, touchY: Float, press: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail?,
    ) {
        material.updateDeformation(left, top, right, bottom, cornerRadius, touchX, touchY, stretch, tail)
        canvas.withClip(material.blurClip()) { drawPatch(this) }
        material.drawBlurOverlay(canvas, press)
    }

    protected fun drawPatch(canvas: Canvas) {
        if (renderScale == 1f) {
            canvas.withTranslation(-inset.toFloat(), -inset.toFloat()) { drawRenderNode(patch) }
        } else {
            val inv = 1f / renderScale
            canvas.withScale(inv, inv) {
                withTranslation(-inset.toFloat(), -inset.toFloat()) { drawRenderNode(patch) }
            }
        }
    }
}

/** Shared rounded clips and a legible surface for canvases without the refraction shader. */
internal class WitLiquidGlassPaint {
    private val clip = Path()
    private val scratchPath = Path()
    private val scratchArcRect = RectF()
    private val deformedBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightMatrix = Matrix()
    private val rimMatrix = Matrix()
    private val light = RadialGradient(0f, 0f, 1f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    private var deformedRadius = 0f
    private var hasMorphTail = false
    private var density = 1f
    private var lightX = Float.NaN
    private var lightY = Float.NaN
    private var lightRadius = 1f
    private var rimWidth = 0f
    private var solidFill = Color.TRANSPARENT
    private var fallbackFill = Color.TRANSPARENT
    private var blurWash = Color.TRANSPARENT
    private var rim: LinearGradient? = null
    internal var rimAlpha: Float = 1f

    internal fun update(
        width: Int, height: Int, density: Float, base: Int, tint: Int,
        dark: Boolean, clear: Boolean, clearDimming: Float,
    ) {
        this.density = density
        rimWidth = maxOf(1f, density * 0.55f)
        lightRadius = maxOf(1f, minOf(width, height) * 0.80f)
        lightX = Float.NaN

        val tintedBase = ColorUtils.blendARGB(base, tint or Color.BLACK, Color.alpha(tint) / 765f)
        solidFill = ColorUtils.setAlphaComponent(tintedBase, 255)
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
                0f, 0f, 1f, 1f,
                intArrayOf(if (dark) 0x64FFFFFF else 0xBBFFFFFF.toInt(), 0x1FFFFFFF, 0x59000000, 0x73FFFFFF),
                floatArrayOf(0f, 0.35f, 0.66f, 1f), Shader.TileMode.CLAMP,
            )
        } else null
    }

    internal fun updateDeformation(
        left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float,
        touchX: Float, touchY: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail? = null,
    ) {
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        if (stretch != 0f && width > 0f && height > 0f && tail?.active != true) {
            val elastic = stretch * minOf(density, minOf(width, height) / 48f)
            val gripX = ((touchX - left) / maxOf(1f, width) * 2f - 1f).coerceIn(-1f, 1f)
            val gripY = ((touchY - top) / maxOf(1f, height) * 2f - 1f).coerceIn(-1f, 1f)
            val expandX = elastic * (1.6f + abs(gripX) * 0.55f)
            val expandY = elastic * (1.6f + abs(gripY) * 0.55f)
            deformedBounds.set(left - expandX, top - expandY, right + expandX, bottom + expandY)
            deformedBounds.offset(gripX * elastic * 1.8f, gripY * elastic * 1.4f)
            val maxRadius = (minOf(deformedBounds.width(), deformedBounds.height()) / 2f).coerceAtLeast(0f)
            deformedRadius = (cornerRadius + elastic * 1.6f).coerceIn(0f, maxRadius)
        } else {
            deformedBounds.set(left, top, right, bottom)
            val maxRadius = (minOf(width, height) / 2f).coerceAtLeast(0f)
            deformedRadius = cornerRadius.coerceIn(0f, maxRadius)
        }

        hasMorphTail = tail?.active == true
        buildLiquidGlassMorphPath(
            clip, scratchPath, scratchArcRect,
            deformedBounds.left, deformedBounds.top, deformedBounds.right, deformedBounds.bottom,
            deformedRadius, tail,
        )

        val dw = deformedBounds.width()
        val dh = deformedBounds.height()
        if (dw > 0f && dh > 0f) {
            rim?.let {
                rimMatrix.setScale(dw, dh)
                rimMatrix.postTranslate(deformedBounds.left, deformedBounds.top)
                it.setLocalMatrix(rimMatrix)
            }
        }

        if (touchX != lightX || touchY != lightY) {
            lightX = touchX
            lightY = touchY
            lightMatrix.setScale(lightRadius, lightRadius)
            lightMatrix.postTranslate(touchX, touchY)
            light.setLocalMatrix(lightMatrix)
        }
    }

    internal fun blurClip(): Path = clip

    internal fun drawFallback(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float,
        touchX: Float, touchY: Float, press: Float, stretch: Float,
        tail: WitLiquidGlassMorphTail? = null,
    ) {
        updateDeformation(left, top, right, bottom, cornerRadius, touchX, touchY, stretch, tail)
        drawSurface(canvas, fallbackFill, press)
    }

    internal fun drawBlurOverlay(canvas: Canvas, press: Float) {
        drawSurface(canvas, blurWash, press)
    }

    /** 绘制纯实色表面：支持动态 baseFill、受控 rimAlpha 反光描边与流体形变。 */
    internal fun drawSolid(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float,
        tail: WitLiquidGlassMorphTail? = null,
    ) {
        updateDeformation(left, top, right, bottom, cornerRadius, 0f, 0f, 0f, tail)
        if (deformedBounds.width() <= 0f || deformedBounds.height() <= 0f) return
        paint.color = solidFill
        if (hasMorphTail && !clip.isEmpty) {
            canvas.drawPath(clip, paint)
        } else {
            canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
        }
        val rimShader = rim
        val effectiveRimAlpha = rimAlpha.coerceIn(0f, 1f)
        if (rimShader != null && effectiveRimAlpha > 0.001f && deformedBounds.width() > rimWidth && deformedBounds.height() > rimWidth) {
            paint.color = Color.WHITE
            paint.alpha = (effectiveRimAlpha * 255f).toInt().coerceIn(0, 255)
            paint.shader = rimShader
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rimWidth
            if (hasMorphTail && !clip.isEmpty) {
                canvas.withClip(clip) {
                    drawPath(clip, paint)
                }
            } else {
                val halfStroke = rimWidth / 2f
                val rimRadius = (deformedRadius - halfStroke).coerceAtLeast(0f)
                canvas.drawRoundRect(
                    deformedBounds.left + halfStroke, deformedBounds.top + halfStroke,
                    deformedBounds.right - halfStroke, deformedBounds.bottom - halfStroke,
                    rimRadius, rimRadius, paint,
                )
            }
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
        }
    }

    private fun drawSurface(canvas: Canvas, fill: Int, press: Float) {
        if (deformedBounds.width() <= 0f || deformedBounds.height() <= 0f) return
        paint.color = fill
        if (hasMorphTail && !clip.isEmpty) {
            canvas.drawPath(clip, paint)
        } else {
            canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
        }
        if (press > 0f) {
            paint.shader = light
            paint.alpha = (press * 180f).toInt()
            if (hasMorphTail && !clip.isEmpty) {
                canvas.drawPath(clip, paint)
            } else {
                canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
            }
        }
        val rimShader = rim
        val effectiveRimAlpha = rimAlpha.coerceIn(0f, 1f)
        if (rimShader != null && effectiveRimAlpha > 0.001f && deformedBounds.width() > rimWidth && deformedBounds.height() > rimWidth) {
            paint.color = Color.WHITE
            paint.alpha = (effectiveRimAlpha * 255f).toInt().coerceIn(0, 255)
            paint.shader = rimShader
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rimWidth
            if (hasMorphTail) {
                canvas.withClip(clip) {
                    drawPath(clip, paint)
                }
            } else {
                val halfStroke = rimWidth / 2f
                val rimRadius = (deformedRadius - halfStroke).coerceAtLeast(0f)
                canvas.drawRoundRect(
                    deformedBounds.left + halfStroke, deformedBounds.top + halfStroke,
                    deformedBounds.right - halfStroke, deformedBounds.bottom - halfStroke,
                    rimRadius, rimRadius, paint,
                )
            }
            paint.style = Paint.Style.FILL
        }
        paint.shader = null
        paint.alpha = 255
    }
}
