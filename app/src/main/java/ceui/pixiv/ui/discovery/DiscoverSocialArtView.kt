package ceui.pixiv.ui.discovery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave

/** Static, resolution-independent miniature. All fills are host V3Palette roles. */
internal class DiscoverSocialArtView(
    context: Context,
    private val colors: DiscoverSocialColors,
    private val chat: Boolean,
) : View(context) {
    private val palette = colors.palette
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val oval = RectF(5f, 24f, 137f, 120f)
    private val backBubble = rounded(9f, 21f, 96f, 81f, 19f, bottomLeft = 5f)
    private val frontBubble = rounded(40f, 59f, 129f, 121f, 20f, bottomRight = 5f)
    private val seal = rounded(15f, 88f, 54f, 124f, 13f, bottomLeft = 5f)
    private val heart = Path().apply {
        moveTo(12f, 21f)
        cubicTo(8f, 17f, 2f, 13f, 2f, 8f)
        cubicTo(2f, 2f, 9f, 1f, 12f, 6f)
        cubicTo(15f, 1f, 22f, 2f, 22f, 8f)
        cubicTo(22f, 13f, 16f, 17f, 12f, 21f)
        close()
    }
    private val imageClip = rounded(53f, 29f, 114f, 76f, 7f)
    private val hill = Path().apply {
        moveTo(46f, 76f); cubicTo(54f, 47f, 81f, 52f, 96f, 76f); close()
    }
    private val frontHill = Path().apply {
        moveTo(78f, 76f); cubicTo(86f, 46f, 109f, 51f, 122f, 61f); lineTo(122f, 76f); close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val scale = minOf(width / 142f, height / 140f)
        canvas.withSave {
            translate((width - 142f * scale) / 2, (height - 140f * scale) / 2)
            scale(scale, scale)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = palette.alpha15
            withRotation(-25f, 71f, 72f) { drawOval(oval, paint) }
            paint.color = palette.alpha08
            drawCircle(71f, 72f, 59f, paint)
            if (chat) drawChat(this) else drawCommunity(this)
        }
    }

    private fun drawChat(canvas: Canvas) = with(canvas) {
        withRotation(-12f, 52.5f, 51f) {
            shadow(this, backBubble)
            fill(palette.cardFill); drawPath(backBubble, paint)
            fill(ColorUtils.compositeColors(palette.alpha30, palette.cardFill))
            drawRoundRect(28f, 42f, 71f, 47f, 3f, 3f, paint)
            drawRoundRect(28f, 54f, 57f, 59f, 3f, 3f, paint)
        }
        withRotation(9f, 84.5f, 90f) {
            shadow(this, frontBubble)
            fill(palette.primary); drawPath(frontBubble, paint)
            fill(colors.onPrimary)
            for (x in floatArrayOf(67f, 84.5f, 102f)) drawCircle(x, 90f, 4f, paint)
        }
        spark(this, 129f, 28f, 11f)
        fill(palette.alpha50); drawCircle(15f, 121f, 2.5f, paint)
    }

    private fun drawCommunity(canvas: Canvas) = with(canvas) {
        withRotation(-15f, 53.5f, 70.5f) {
            fill(ColorUtils.compositeColors(palette.alpha20, palette.cardFill))
            drawRoundRect(16f, 25f, 91f, 116f, 13f, 13f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = palette.alpha15
            drawRoundRect(16f, 25f, 91f, 116f, 13f, 13f, paint)
            fill(palette.alpha50)
            drawRoundRect(27f, 40f, 53f, 44f, 2f, 2f, paint)
            drawRoundRect(27f, 51f, 45f, 55f, 2f, 2f, paint)
        }
        withRotation(10f, 83.5f, 67.5f) {
            fill(palette.alpha08); drawRoundRect(47f, 26f, 122f, 117f, 13f, 13f, paint)
            fill(palette.cardFill); drawRoundRect(46f, 22f, 121f, 113f, 13f, 13f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = palette.cardHairline
            drawRoundRect(46f, 22f, 121f, 113f, 13f, 13f, paint)
            fill(ColorUtils.compositeColors(palette.alpha20, palette.cardFill)); drawPath(imageClip, paint)
            withSave {
                clipPath(imageClip)
                fill(ColorUtils.compositeColors(palette.alpha60, palette.cardFill)); drawPath(hill, paint)
                fill(palette.primary); drawPath(frontHill, paint)
            }
            spark(this, 101f, 44f, 8f)
            fill(ColorUtils.compositeColors(palette.alpha15, palette.cardFill))
            drawRoundRect(53f, 84f, 93f, 88f, 2f, 2f, paint)
            drawRoundRect(53f, 93f, 79f, 97f, 2f, 2f, paint)
        }
        withRotation(-12f, 34.5f, 106f) {
            fill(palette.primary); drawPath(seal, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = palette.cardFill
            drawPath(seal, paint)
            withSave {
                translate(24f, 96f); scale(.85f, .85f)
                fill(colors.onPrimary); drawPath(heart, paint)
            }
        }
        fill(palette.alpha50); drawCircle(134f, 28f, 2.5f, paint)
    }

    private fun shadow(canvas: Canvas, path: Path) = canvas.withSave {
        translate(0f, 3f); fill(palette.alpha08); drawPath(path, paint)
    }

    private fun spark(canvas: Canvas, x: Float, y: Float, radius: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = palette.textAccent
        canvas.drawLine(x - radius, y, x + radius, y, paint)
        canvas.drawLine(x, y - radius, x, y + radius, paint)
        val diagonal = radius * .70f
        canvas.drawLine(x - diagonal, y - diagonal, x + diagonal, y + diagonal, paint)
        canvas.drawLine(x - diagonal, y + diagonal, x + diagonal, y - diagonal, paint)
    }

    private fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL }

    private fun rounded(left: Float, top: Float, right: Float, bottom: Float, radius: Float,
                        bottomLeft: Float = radius, bottomRight: Float = radius) = Path().apply {
        addRoundRect(RectF(left, top, right, bottom),
            floatArrayOf(radius, radius, radius, radius, bottomRight, bottomRight, bottomLeft, bottomLeft), Path.Direction.CW)
    }
}
