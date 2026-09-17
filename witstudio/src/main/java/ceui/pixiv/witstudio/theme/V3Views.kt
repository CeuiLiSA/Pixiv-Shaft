package ceui.pixiv.witstudio.theme

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import ceui.pixiv.witstudio.R

/**
 * V3 语汇的公共零件：字号字重、表面、按压反馈与热区，照 `docs/v3-design-philosophy.md`。
 *
 * 英文与数字走 Montserrat（字体文件就在本模块 `res/font`，`:app` 的 `@font/montserrat_*`
 * 由资源合并继续指到这里），中文走系统回退；22dp 卡片是 [V3Palette.cardFill] 加一条
 * 12% hairline；连通分段行外角 20 内角 5（与 [WitRowStyle] 同一套关系，区别只是这里
 * 在运行时算，那边是四张 drawable）；胶囊动作按下缩到 0.96，热区至少 48dp。
 *
 * 这里不含任何页面业务 —— 广场（帖子 / 举报 / 屏蔽名单）、版本历史与更新弹窗共用同一份。
 * 顶层函数名（[dp] / [color] / [card] / [shape]）刻意短，只对显式 import 的文件可见；
 * 宿主若另有同名扩展，在该调用点显式限定即可。
 */

public fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

public fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

public fun Context.color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

/** Montserrat carries Latin and digits; CJK falls back to the system font, as on every V3 page. */
public fun Context.v3Font(weight: Int): Typeface =
    ResourcesCompat.getFont(
        this,
        when {
            weight >= 800 -> R.font.montserrat_extra_bold
            weight >= 700 -> R.font.montserrat_bold
            weight >= 600 -> R.font.montserrat_semi_bold
            weight >= 500 -> R.font.montserrat_medium
            else -> R.font.montserrat_regular
        },
    ) ?: Typeface.DEFAULT

public fun Context.label(
    value: CharSequence,
    size: Float = 14f,
    weight: Int = 400,
    @ColorInt color: Int = color(R.color.wit_text_1),
): TextView =
    AppCompatTextView(this).apply {
        text = value
        textSize = size
        typeface = v3Font(weight)
        setTextColor(color)
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = true
    }

public fun TextView.lineHeightRatio(multiplier: Float) {
    setLineSpacing(0f, 1f)
    TextViewCompat.setLineHeight(this, (textSize * multiplier).toInt())
}

/** Section label shared with the V3 artwork page (`item_v3_section_label`). */
public fun Context.sectionLabel(value: CharSequence): TextView =
    label(value, 12f, 700, color(R.color.wit_text_3)).apply {
        isAllCaps = true
        letterSpacing = .12f
        ViewCompat.setAccessibilityHeading(this, true)
    }

public fun motionEnabled(): Boolean =
    Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

/** V3 press response: scale to 0.96 on touch, release in 200ms; off when animations are off. */
@SuppressLint("ClickableViewAccessibility")
public fun View.pressScale(scale: Float = .96f) {
    setOnTouchListener { v, event ->
        if (motionEnabled()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
        false
    }
}

public fun Context.hairlinePx(): Int = dp(1).coerceAtLeast(1)

public fun shape(
    radiusPx: Float,
    @ColorInt fill: Int,
    @ColorInt stroke: Int? = null,
    strokePx: Int = 0,
): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (stroke != null && strokePx > 0) setStroke(strokePx, stroke)
    }

public fun Context.ripple(content: Drawable?, mask: Drawable): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(V3Palette.from(this).alpha20), content, mask)

/** 22dp content card: theme-tinted fill and a 12% hairline, near-zero elevation. */
public fun Context.card(radius: Int = 22): GradientDrawable {
    val p = V3Palette.from(this)
    return shape(dpF(radius.toFloat()), p.cardFill, p.cardHairline, hairlinePx())
}

public fun Context.cardSurface(radius: Int = 22): RippleDrawable =
    ripple(card(radius), shape(dpF(radius.toFloat()), Color.WHITE))

/** Connected segmented row: 20dp outer corners, 5dp inner corners, 2dp gap between rows. */
public fun Context.rowShape(index: Int, total: Int, @ColorInt fill: Int? = null): GradientDrawable {
    val p = V3Palette.from(this)
    val outer = dpF(20f)
    val inner = dpF(5f)
    val top = if (index <= 0) outer else inner
    val bottom = if (index >= total - 1) outer else inner
    return GradientDrawable().apply {
        cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        setColor(fill ?: p.cardFill)
        setStroke(hairlinePx(), p.cardHairline)
    }
}

public fun Context.rowSurface(index: Int, total: Int): RippleDrawable =
    ripple(rowShape(index, total), rowShape(index, total, Color.WHITE))

/** 48dp icon button with a circular ripple; the glyph reads as a theme-tinted idle control. */
public class IconButton(
    context: Context,
    @DrawableRes drawable: Int,
    description: String,
    @ColorInt tint: Int = V3Palette.from(context).floatingPillContent,
) : FrameLayout(context) {
    public val icon: ImageView =
        ImageView(context).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(tint)
        }

    init {
        minimumWidth = context.dp(48)
        minimumHeight = context.dp(48)
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = context.ripple(null, shape(999f, Color.WHITE))
        addView(icon, LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER))
    }
}

/** Pill action: primary = solid theme colour, tonal = 20% tint with a 30% stroke. */
public fun Context.pillButton(
    text: CharSequence,
    primary: Boolean = true,
    @DrawableRes icon: Int? = null,
    action: () -> Unit,
): TextView {
    val p = V3Palette.from(this)
    val fill = if (primary) p.pillPrimary(999f) else p.pillSecondary(999f, hairlinePx())
    return label(text, 14f, 600, if (primary) p.onPrimary else p.textAccent).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(48)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = ripple(fill, shape(999f, Color.WHITE))
        icon?.let {
            val d = ContextCompat.getDrawable(context, it)?.mutate()?.apply {
                setTint(currentTextColor)
                setBounds(0, 0, dp(18), dp(18))
            }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        pressScale()
    }
}

/** 类别图标容器：17/17/17/7 圆角的主题浅底，只在图标区出现，是页面里唯一的异形。 */
public fun Context.iconTile(
    @DrawableRes icon: Int,
    size: Int = 48,
    @ColorInt tint: Int = V3Palette.from(this).textAccent,
    @ColorInt fill: Int = V3Palette.from(this).alpha15,
): FrameLayout {
    val r = dpF(17f)
    val cut = dpF(7f)
    return FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, r, r, cut, cut)
            setColor(fill)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            },
            FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER),
        )
    }
}

/** 一行说明 + 图标的浅色提示卡，用于页面主区之前交代规则。 */
public fun Context.noticeCard(@DrawableRes icon: Int, text: CharSequence): LinearLayout {
    val palette = V3Palette.from(this)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = card(22)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(palette.textAccent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(2) },
        )
        addView(
            label(text, 13f, 400, color(R.color.wit_text_2)).apply { lineHeightRatio(1.6f) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) },
        )
    }
}

/** 末端小号胶囊动作：行内用，仍保证 48dp 热区。 */
public fun Context.compactPill(text: CharSequence, action: () -> Unit): TextView =
    pillButton(text, primary = false, action = action).apply {
        textSize = 13f
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
