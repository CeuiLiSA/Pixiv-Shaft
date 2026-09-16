package ceui.pixiv.ui.referral

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import kotlin.math.roundToInt

/** Page-scoped roles: calibrated against the approved mockup, hue follows the host V3 theme. */
internal class ReferralColors(context: Context, darkOverride: Boolean?, accentOverride: Int?) {
    private val host = V3Palette.from(context)
    val dark = darkOverride ?: host.isDark
    private val source = accentOverride ?: host.primary
    private val referenceHue = hsl(0xFF6A4BC5.toInt())[0]
    private val hue = hsl(source)[0]
    private fun themed(hex: String): Int {
        val value = hsl(Color.parseColor(hex))
        value[0] = (value[0] + hue - referenceHue + 360) % 360
        return ColorUtils.HSLToColor(value)
    }
    private fun mode(light: String, night: String) = Color.parseColor(if (dark) night else light)
    val bg = mode("#F8F7FC", "#101014")
    val surface = mode("#FFFFFF", "#1B1A20")
    val surface2 = mode("#F0EEF6", "#24222B")
    val surface3 = mode("#E9E5F2", "#302B3A")
    val ink = mode("#262331", "#F3EFFA")
    val muted = mode("#716C80", "#B0A8BD")
    val hero = themed(if (dark) "#282236" else "#EAE3F7")
    val onHero = contrast(mode("#625B72", "#B0A8BD"), hero)
    val tint = themed(if (dark) "#3C2E56" else "#EAE2FF")
    val onTint = contrast(themed(if (dark) "#DFD0FF" else "#493478"), tint)
    val primary = if (dark) themed("#C5AFFF") else contrast(source, surface)
    val onPrimary = if (ColorUtils.calculateContrast(Color.WHITE, primary) >= 4.5) Color.WHITE else Color.parseColor("#30204E").let { contrast(it, primary) }
    val line = mode("#E7E3EE", "#34303E")
    val green = mode("#386746", "#B7D8AB")
    val greenBg = mode("#E9F1E5", "#283527")
    val danger = mode("#A93443", "#FFB2B9")
    val peach = mode("#965F40", "#E8B695")
    val peachBg = mode("#F9E9E0", "#423027")
    val blue = mode("#516795", "#B4C7EE")
    val blueBg = mode("#E6EDF9", "#293248")
    val artMonth = themed("#C5B0EC")
    val artInk = themed("#33244E")

    private fun contrast(foreground: Int, background: Int): Int {
        if (ColorUtils.calculateContrast(foreground, background) >= 4.5) return foreground
        val target = if (ColorUtils.calculateLuminance(background) < .4) Color.WHITE else Color.BLACK
        for (step in 1..100) {
            val candidate = ColorUtils.blendARGB(foreground, target, step / 100f)
            if (ColorUtils.calculateContrast(candidate, background) >= 4.5) return candidate
        }
        return target
    }
    private fun hsl(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
}

internal enum class ReferralIcon(val path: String) {
    SPARK("M12,3 L14.6,9.4 L21,12 L14.6,14.6 L12,21 L9.4,14.6 L3,12 L9.4,9.4 Z"),
    ARROW("M5,12 L19,12 M13,6 L19,12 L13,18"),
    BACK("M19,12 L5,12 M11,6 L5,12 L11,18"),
    USER("M12,8 A3,3 0,1 1,6,8 A3,3 0,1 1,12,8 M3,20 L3,18 A6,6 0,0 1,15,18 L15,20 M19,7 L19,13 M16,10 L22,10"),
    STACK("M10,3 L17,3 Q20,3 20,6 L20,15 Q20,18 17,18 L10,18 Q7,18 7,15 L7,6 Q7,3 10,3 M16,21 L6,21 Q3,21 3,18 L3,8 M10,13 L13,10 L17,14"),
    PLAY("M7,4 L17,4 Q21,4 21,8 L21,16 Q21,20 17,20 L7,20 Q3,20 3,16 L3,8 Q3,4 7,4 M10,9 L15,12 L10,15 Z"),
    HEART("M20.8,4.6 C18.6,2.5 15.2,2.5 13,4.6 L12,5.7 L10.9,4.6 C8.7,2.5 5.3,2.5 3.1,4.6 C1,6.8 1,10.2 3.1,12.4 L12,21 L20.8,12.4 C23,10.2 23,6.8 20.8,4.6 Z"),
    TICKET("M3,5 L21,5 L21,10 A2,2 0,0 0,21,14 L21,19 L3,19 L3,14 A2,2 0,0 0,3,10 Z M15,5 L15,8 M15,11 L15,13 M15,16 L15,19"),
    MOON("M20.6,14 A9,9 0,0 1,10,3.4 A9,9 0,1 0,20.6,14 Z"),
    CHECK("M5,12 L9,16 L19,6"),
    CLOSE("M6,6 L18,18 M6,18 L18,6"),
    COPY("M10,8 L18,8 Q20,8 20,10 L20,19 Q20,21 18,21 L10,21 Q8,21 8,19 L8,10 Q8,8 10,8 M16,8 L16,5 Q16,3 14,3 L5,3 Q3,3 3,5 L3,14 Q3,16 5,16 L8,16"),
}

internal class ReferralIconDrawable(icon: ReferralIcon, color: Int, private val side: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(icon.path)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        path?.let { canvas.drawPath(it, paint) }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = side
    override fun getIntrinsicHeight() = side
}

internal class ReferralUi(val context: Context, val colors: ReferralColors) {
    private val density = context.resources.displayMetrics.density
    private val fonts = mutableMapOf<Int, Typeface>()
    fun dp(value: Int) = (value * density).roundToInt()
    fun dp(value: Float) = value * density
    fun s(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)
    fun font(weight: Int): Typeface = fonts.getOrPut(weight) {
        ResourcesCompat.getFont(context, when (weight) {
            800 -> R.font.montserrat_extra_bold
            700 -> R.font.montserrat_bold
            600 -> R.font.montserrat_semi_bold
            500 -> R.font.montserrat_medium
            else -> R.font.montserrat_regular
        }) ?: Typeface.DEFAULT
    }
    fun text(value: CharSequence, size: Float = 14f, weight: Int = 400, color: Int = colors.ink) =
        AppCompatTextView(context).apply {
            text = value; textSize = size; typeface = font(weight); setTextColor(color)
            includeFontPadding = false; setLineSpacing(0f, 1.3f)
            if (Build.VERSION.SDK_INT >= 28) { isFallbackLineSpacing = true }
        }
    fun text(@StringRes id: Int, size: Float = 14f, weight: Int = 400, color: Int = colors.ink) = text(s(id), size, weight, color)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun shape(color: Int, radius: Float = 22f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius)
        stroke?.let { setStroke(dp(1), it) }
    }
    fun icon(icon: ReferralIcon, color: Int = colors.ink, size: Int = 20) = ReferralIconDrawable(icon, color, dp(size))
    fun button(value: CharSequence, primary: Boolean = false, outline: Boolean = false,
               icon: ReferralIcon? = null, action: () -> Unit): TextView = text(value, 12f, 600,
        if (primary) colors.onPrimary else colors.onTint).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val fill = if (primary) colors.primary else if (outline) Color.TRANSPARENT else colors.tint
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors.primary, 35)),
            shape(fill, 999f, if (outline) colors.line else null), shape(Color.WHITE, 999f))
        icon?.let { setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
            this@ReferralUi.icon(it, currentTextColor, 16), null); compoundDrawablePadding = dp(10) }
        isFocusable = true
        setOnClickListener { action() }
        setOnTouchListener { v, event ->
            if (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(.96f).scaleY(.96f).setDuration(120).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
            false
        }
    }
    fun button(@StringRes id: Int, primary: Boolean = false, outline: Boolean = false,
               icon: ReferralIcon? = null, action: () -> Unit) = button(s(id), primary, outline, icon, action)
    fun iconButton(icon: ReferralIcon, @StringRes label: Int, action: () -> Unit): TextView =
        button("", outline = true, action = action).apply {
            background = RippleDrawable(ColorStateList.valueOf(colors.tint), null, shape(Color.WHITE, 999f))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setCompoundDrawablesRelativeWithIntrinsicBounds(this@ReferralUi.icon(icon), null, null, null)
            contentDescription = s(label)
        }
    fun heading(view: TextView): TextView = view.apply { ViewCompat.setAccessibilityHeading(this, true) }
    fun add(parent: LinearLayout, child: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) {
        parent.addView(child, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(top) })
    }
    fun space(parent: LinearLayout, height: Int) { add(parent, View(context), height = dp(height)) }
}

internal data class ReferralTaskCopy(@StringRes val title: Int, @StringRes val category: Int,
    @StringRes val description: Int, @StringRes val caption: Int, @StringRes val steps: Int,
    @StringRes val condition: Int, val icon: ReferralIcon)

internal fun ReferralTask.copy() = when (this) {
    ReferralTask.INVITE -> ReferralTaskCopy(R.string.referral_invite_title_task, R.string.referral_invite_category,
        R.string.referral_invite_description, R.string.referral_invite_caption, R.string.referral_invite_steps,
        R.string.referral_invite_condition_task, ReferralIcon.USER)
    ReferralTask.RECOMMEND -> ReferralTaskCopy(R.string.referral_recommend_title, R.string.referral_recommend_category,
        R.string.referral_recommend_description, R.string.referral_recommend_caption, R.string.referral_recommend_steps,
        R.string.referral_recommend_condition, ReferralIcon.STACK)
    ReferralTask.TUTORIAL -> ReferralTaskCopy(R.string.referral_tutorial_title, R.string.referral_tutorial_category,
        R.string.referral_tutorial_description, R.string.referral_tutorial_caption, R.string.referral_tutorial_steps,
        R.string.referral_tutorial_condition, ReferralIcon.PLAY)
    ReferralTask.CIRCLE -> ReferralTaskCopy(R.string.referral_circle_title, R.string.referral_circle_category,
        R.string.referral_circle_description, R.string.referral_circle_caption, R.string.referral_circle_steps,
        R.string.referral_circle_condition, ReferralIcon.HEART)
}

internal val ReferralStatus.label: Int get() = when (this) {
    ReferralStatus.NEW -> R.string.referral_state_new
    ReferralStatus.PROGRESS -> R.string.referral_progress
    ReferralStatus.PENDING -> R.string.referral_state_pending
    ReferralStatus.REJECTED -> R.string.referral_state_rejected
    ReferralStatus.READY -> R.string.referral_ready
    ReferralStatus.CLAIMED -> R.string.referral_state_claimed
}
