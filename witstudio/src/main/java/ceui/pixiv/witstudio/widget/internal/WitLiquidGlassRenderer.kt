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
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class WitLiquidGlassMorphTail(
    var orbX: Float = 0f,
    var orbY: Float = 0f,
    var orbRadius: Float = 0f,
    var blendPx: Float = 0f,
) {
    val active: Boolean get() = orbRadius > 0.5f && blendPx > 0.01f

    fun clear() {
        orbX = 0f
        orbY = 0f
        orbRadius = 0f
        blendPx = 0f
    }
}

private fun evalRoundRectSdf(
    px: Float,
    py: Float,
    cx: Float,
    cy: Float,
    halfW: Float,
    halfH: Float,
    rad: Float,
): Float {
    val qx = abs(px - cx) - halfW + rad
    val qy = abs(py - cy) - halfH + rad
    val ox = qx.coerceAtLeast(0f)
    val oy = qy.coerceAtLeast(0f)
    return hypot(ox, oy) + minOf(maxOf(qx, qy), 0f) - rad
}

private fun evalSmoothMin(a: Float, b: Float, k: Float): Float {
    if (k <= 0.001f) return minOf(a, b)
    val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
    return b * (1f - h) + a * h - k * h * (1f - h)
}

/**
 * Builds the closed metaball-union silhouette: Body B rounded rect smoothly joined to the
 * anchor orb circle via an smin-calibrated concave liquid bridge. Used for child clipping and
 * non-AGSL glass backends so every API level shares the same liquid droplet contour.
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
    val hasBody = w > 0.5f && h > 0.5f
    val hasOrb = tail != null && tail.active
    if (!hasBody && !hasOrb) return
    if (!hasBody && hasOrb) {
        outPath.addCircle(tail!!.orbX, tail.orbY, tail.orbRadius, Path.Direction.CW)
        return
    }
    val rad = cornerRadius.coerceIn(0f, minOf(w, h) * 0.5f)
    outPath.addRoundRect(left, top, right, bottom, rad, rad, Path.Direction.CW)
    if (!hasOrb) return

    val orbX = tail!!.orbX
    val orbY = tail.orbY
    val rA = tail.orbRadius
    val k = tail.blendPx

    // Union the orb circle into the silhouette first.
    scratchPath.rewind()
    scratchPath.addCircle(orbX, orbY, rA, Path.Direction.CW)
    val orbMerged = try {
        outPath.op(scratchPath, Path.Op.UNION)
    } catch (_: Throwable) {
        false
    }
    if (!orbMerged || outPath.isEmpty) {
        outPath.rewind()
        outPath.addRoundRect(left, top, right, bottom, rad, rad, Path.Direction.CW)
        outPath.addCircle(orbX, orbY, rA, Path.Direction.CW)
    }

    // Closest inscribed corner circle of Body B toward the orb defines the natural neck axis.
    val bCx = orbX.coerceIn(left + rad, right - rad)
    val bCy = orbY.coerceIn(top + rad, bottom - rad)
    val rB = rad.coerceAtLeast(1f)
    val dx = bCx - orbX
    val dy = bCy - orbY
    val dist = hypot(dx, dy)
    if (dist <= abs(rA - rB) + 0.5f || dist >= rA + rB + k * 0.65f) return

    val ux = dx / dist
    val uy = dy / dist
    val perpX = -uy
    val perpY = ux
    val sMid = (0.5f * (dist + rA - rB)).coerceIn(dist * 0.15f, dist * 0.85f)
    val mx = orbX + ux * sMid
    val my = orbY + uy * sMid

    val bodyCx = (left + right) * 0.5f
    val bodyCy = (top + bottom) * 0.5f
    val halfW = w * 0.5f
    val halfH = h * 0.5f

    fun sdfAt(x: Float, y: Float): Float {
        val dOrb = hypot(x - orbX, y - orbY) - rA
        val dBody = evalRoundRectSdf(x, y, bodyCx, bodyCy, halfW, halfH, rad)
        return evalSmoothMin(dOrb, dBody, k)
    }

    // Center of neck must be inside the smin=0 liquid bridge.
    if (sdfAt(mx, my) >= 0f) return

    // Bisect the exact smin=0 concave waist width independently on both sides of the neck axis.
    val maxReach = maxOf(rA, rB) + k + 2f
    fun bisectWaist(sign: Float): Float {
        var lo = 0f
        var hi = maxReach
        repeat(7) {
            val mid = (lo + hi) * 0.5f
            if (sdfAt(mx + perpX * sign * mid, my + perpY * sign * mid) < 0f) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return (lo + hi) * 0.5f
    }

    val wPos = bisectWaist(1f)
    val wNeg = bisectWaist(-1f)
    val w1X = mx + perpX * wPos
    val w1Y = my + perpY * wPos
    val w2X = mx - perpX * wNeg
    val w2Y = my - perpY * wNeg

    val theta = atan2(dy, dx)
    val beta = acos(((rA - rB) / dist).coerceIn(-0.98f, 0.98f))
    val cosP = cos(theta + beta).toFloat()
    val sinP = sin(theta + beta).toFloat()
    val cosN = cos(theta - beta).toFloat()
    val sinN = sin(theta - beta).toFloat()

    val p1Ax = orbX + rA * cosP
    val p1Ay = orbY + rA * sinP
    val p1Bx = bCx + rB * cosP
    val p1By = bCy + rB * sinP

    val p2Ax = orbX + rA * cosN
    val p2Ay = orbY + rA * sinN
    val p2Bx = bCx + rB * cosN
    val p2By = bCy + rB * sinN

    // Quadratic Bezier control points passing exactly through the smin=0 concave waist points.
    val c1X = 2f * w1X - 0.5f * (p1Ax + p1Bx)
    val c1Y = 2f * w1Y - 0.5f * (p1Ay + p1By)
    val c2X = 2f * w2X - 0.5f * (p2Ax + p2Bx)
    val c2Y = 2f * w2Y - 0.5f * (p2Ay + p2By)

    scratchPath.rewind()
    scratchPath.moveTo(p1Ax, p1Ay)
    scratchPath.quadTo(c1X, c1Y, p1Bx, p1By)
    scratchPath.lineTo(bCx, bCy)
    scratchPath.lineTo(p2Bx, p2By)
    scratchPath.quadTo(c2X, c2Y, p2Ax, p2Ay)
    scratchPath.lineTo(orbX, orbY)
    scratchPath.close()

    val bridgeMerged = try {
        outPath.op(scratchPath, Path.Op.UNION)
    } catch (_: Throwable) {
        false
    }
    if (!bridgeMerged || outPath.isEmpty) {
        outPath.rewind()
        outPath.addRoundRect(left, top, right, bottom, rad, rad, Path.Direction.CW)
        outPath.addCircle(orbX, orbY, rA, Path.Direction.CW)
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

        val dw = if (hasMorphTail && tail != null) {
            val minX = if (width > 0f) minOf(deformedBounds.left, tail.orbX - tail.orbRadius) else tail.orbX - tail.orbRadius
            val maxX = if (width > 0f) maxOf(deformedBounds.right, tail.orbX + tail.orbRadius) else tail.orbX + tail.orbRadius
            (maxX - minX).coerceAtLeast(0f)
        } else {
            deformedBounds.width()
        }
        val dh = if (hasMorphTail && tail != null) {
            val minY = if (height > 0f) minOf(deformedBounds.top, tail.orbY - tail.orbRadius) else tail.orbY - tail.orbRadius
            val maxY = if (height > 0f) maxOf(deformedBounds.bottom, tail.orbY + tail.orbRadius) else tail.orbY + tail.orbRadius
            (maxY - minY).coerceAtLeast(0f)
        } else {
            deformedBounds.height()
        }
        val rimLeft = if (hasMorphTail && tail != null) {
            if (width > 0f) minOf(deformedBounds.left, tail.orbX - tail.orbRadius) else tail.orbX - tail.orbRadius
        } else {
            deformedBounds.left
        }
        val rimTop = if (hasMorphTail && tail != null) {
            if (height > 0f) minOf(deformedBounds.top, tail.orbY - tail.orbRadius) else tail.orbY - tail.orbRadius
        } else {
            deformedBounds.top
        }
        if (dw > 0f && dh > 0f) {
            rim?.let {
                rimMatrix.setScale(dw, dh)
                rimMatrix.postTranslate(rimLeft, rimTop)
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
        val hasBounds = deformedBounds.width() > 0f && deformedBounds.height() > 0f
        if (!hasBounds && !(hasMorphTail && !clip.isEmpty)) return
        paint.color = solidFill
        if (hasMorphTail && !clip.isEmpty) {
            canvas.drawPath(clip, paint)
        } else {
            canvas.drawRoundRect(deformedBounds, deformedRadius, deformedRadius, paint)
        }
        val rimShader = rim
        val effectiveRimAlpha = rimAlpha.coerceIn(0f, 1f)
        val canStrokeRim = if (hasMorphTail) !clip.isEmpty else (deformedBounds.width() > rimWidth && deformedBounds.height() > rimWidth)
        if (rimShader != null && effectiveRimAlpha > 0.001f && canStrokeRim) {
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
        val hasBounds = deformedBounds.width() > 0f && deformedBounds.height() > 0f
        if (!hasBounds && !(hasMorphTail && !clip.isEmpty)) return
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
        val canStrokeRim = if (hasMorphTail) !clip.isEmpty else (deformedBounds.width() > rimWidth && deformedBounds.height() > rimWidth)
        if (rimShader != null && effectiveRimAlpha > 0.001f && canStrokeRim) {
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
