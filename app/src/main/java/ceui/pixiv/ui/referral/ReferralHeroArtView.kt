package ceui.pixiv.ui.referral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import ceui.lisa.R
import kotlin.math.cos
import kotlin.math.sin

/** Decorative vector artwork only; all interactive content lives in ordinary accessible Views. */
internal class ReferralHeroArtView(context: Context, private val ui: ReferralUi) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val c = ui.colors
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
        val widthDp = width / resources.displayMetrics.density
        if (widthDp < 650) {
            // Same 250×290 artboard, center origin, .62 scale and right crop as the mobile mockup.
            canvas.translate(widthDp - 107.5f, 149.1f)
            canvas.scale(.62f, .62f)
            orbit(canvas, -60f, 20f, 330f, 270f, -27f)
            orbit(canvas, -60f, 83f, 350f, 220f, 35f)
            star(canvas, 190f, -5f, 44f, c.onTint, 6)
            ticket(canvas, -35f, 10f, -14f, false)
            ticket(canvas, 55f, 90f, 12f, true)
            sticker(canvas, 10f, 225f)
        } else {
            canvas.translate(widthDp - 450f, 30f)
            orbit(canvas, 0f, 25f, 390f, 270f, -27f)
            orbit(canvas, 0f, 65f, 390f, 220f, 35f)
            star(canvas, 365f, 25f, 46f, c.onTint, 6)
            ticket(canvas, 20f, 10f, -14f, false)
            ticket(canvas, 190f, 55f, 12f, true)
            sticker(canvas, 112f, 250f)
        }
        canvas.restoreToCount(save)
    }

    private fun orbit(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, angle: Float) {
        val save = canvas.save(); canvas.rotate(angle, x + w / 2, y + h / 2)
        paint.color = c.primary; paint.alpha = 30; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        rect.set(x, y, x + w, y + h); canvas.drawOval(rect, paint)
        paint.alpha = 255; paint.style = Paint.Style.FILL; canvas.restoreToCount(save)
    }

    private fun ticket(canvas: Canvas, x: Float, y: Float, angle: Float, month: Boolean) {
        val save = canvas.save(); canvas.translate(x, y); canvas.rotate(angle, 107f, 132f)
        for (i in 5 downTo 1) {
            paint.color = Color.argb(3, 50, 37, 82)
            rect.set(-i.toFloat(), 4f, 214f + i, 270f + i)
            canvas.drawRoundRect(rect, 18f, 18f, paint)
        }
        paint.color = if (month) c.artMonth else Color.parseColor("#FCFBF4")
        rect.set(0f, 0f, 214f, 264f); canvas.drawRoundRect(rect, 18f, 18f, paint)
        val ink = if (month) c.artInk else Color.parseColor("#423650")
        text(canvas, "EXPERIENCE PASS", 21f, 34f, 7f, 600, ink)
        star(canvas, 183f, 29f, 8f, ink, 4)
        text(canvas, "PRO", 21f, 114f, 56f, 800, ink)
        text(canvas, "P A S S", 22f, 132f, 11f, 500, ink)
        paint.color = ink; paint.alpha = 65; paint.strokeWidth = 1f
        paint.pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        canvas.drawLine(0f, 182f, 214f, 182f, paint)
        paint.pathEffect = null; paint.alpha = 255
        paint.color = c.hero
        canvas.drawCircle(0f, 182f, 8f, paint); canvas.drawCircle(214f, 182f, 8f, paint)
        text(canvas, if (month) "30" else "7", 21f, 232f, 39f, 500, ink)
        text(canvas, ui.s(R.string.referral_day_unit), if (month) 76f else 48f, 231f, 12f, 400, ink)
        text(canvas, ui.s(if (month) R.string.referral_month_caption else R.string.referral_week_caption), 104f, 211f, 8f, 400, ink)
        text(canvas, if (month) "30-DAY ACCESS" else "7-DAY ACCESS", 104f, 224f, 6.7f, 400, ink)
        canvas.restoreToCount(save)
    }

    private fun sticker(canvas: Canvas, x: Float, y: Float) {
        val save = canvas.save(); canvas.translate(x, y); canvas.rotate(-9f, 43f, 43f)
        paint.color = Color.parseColor("#DBEDB4")
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, 86f, 86f), floatArrayOf(28f, 28f, 39f, 39f, 26f, 26f, 34f, 34f), Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
        val ink = Color.parseColor("#425232")
        text(canvas, ui.s(R.string.referral_pass_badge), 27f, 37f, 13f, 500, ink)
        text(canvas, ui.s(R.string.referral_trial_badge), 22f, 61f, 18f, 700, ink)
        star(canvas, 68f, 20f, 5f, ink, 4)
        canvas.restoreToCount(save)
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, weight: Int, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = size
        paint.typeface = ui.font(weight)
        canvas.drawText(value, x, y, paint)
    }

    private fun star(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, rays: Int) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = if (radius > 20) 2f else 1f
        val path = Path()
        if (rays == 4) {
            path.moveTo(x, y - radius); path.lineTo(x + radius * .3f, y - radius * .3f)
            path.lineTo(x + radius, y); path.lineTo(x + radius * .3f, y + radius * .3f)
            path.lineTo(x, y + radius); path.lineTo(x - radius * .3f, y + radius * .3f)
            path.lineTo(x - radius, y); path.lineTo(x - radius * .3f, y - radius * .3f); path.close()
        } else for (i in 0 until rays) {
            val angle = Math.PI * i / rays
            path.moveTo(x - cos(angle).toFloat() * radius, y - sin(angle).toFloat() * radius)
            path.lineTo(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
        }
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
    }
}
