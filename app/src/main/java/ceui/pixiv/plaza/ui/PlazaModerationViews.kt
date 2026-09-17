package ceui.pixiv.plaza.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette

// V3 pieces shared by the report form, the block confirmation and the block list.
// Shapes, type and hot zones come from docs/v3-design-philosophy.md: 22dp cards, connected
// 20/5 rows, 17/17/17/7 icon containers, the one-off success badge, pill actions, 48dp targets.

/** 类别图标容器：17/17/17/7 圆角的主题浅底，只在图标区出现，是页面里唯一的异形。 */
internal fun Context.iconTile(
    icon: Int,
    size: Int = 48,
    tint: Int = V3Palette.from(this).textAccent,
    fill: Int = V3Palette.from(this).alpha15,
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

/** 分区标题 + 末端的「必选 / 选填」小标，让表单一眼看出哪一段不能跳过。 */
internal fun Context.formSection(title: CharSequence, tag: CharSequence, required: Boolean): LinearLayout {
    val palette = V3Palette.from(this)
    val heading = label(title, 20f, 700).apply {
        lineHeightRatio(1.4f)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    // 小标承载的是「这段能不能跳过」，是信息不是装饰：必选走对比度已校正的 textAccent，
    // 选填走 muted。v3_text_3 只有 33% alpha，压在 v3_surface_2 上实测 2.05:1，不能拿来写字。
    val badge = label(tag, 11f, 600, if (required) palette.textAccent else color(R.color.v3_text_2)).apply {
        letterSpacing = .04f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = shape(999f, if (required) palette.alpha15 else color(R.color.v3_surface_2))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
    }
}

/**
 * 单选行末端的指示器：未选是一圈描边，选中是实心主题色圆点加对勾。
 *
 * 用它替掉系统 RadioButton 的默认圆点 —— 那颗点跟着 AppCompat 的控件语言走，和 V3 的
 * 连通分段行拼在一起像两套界面。控件本身仍是 RadioButton，读屏、分组和状态恢复不变。
 */
internal fun Context.selectionIndicator(size: Int = 22): StateListDrawable {
    val palette = V3Palette.from(this)
    val side = dp(size)
    val checked = LayerDrawable(
        arrayOf(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.primary) },
            InsetDrawable(
                ContextCompat.getDrawable(this, R.drawable.ic_check_24dp)?.mutate()?.apply {
                    setTint(palette.onPrimary)
                },
                dp(4),
            ),
        )
    ).apply { setBounds(0, 0, side, side) }
    val idle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2).coerceAtLeast(2), V3Palette.withAlpha(palette.textSecondary, .55f))
    }.apply { setBounds(0, 0, side, side) }
    return StateListDrawable().apply {
        setBounds(0, 0, side, side)
        addState(intArrayOf(android.R.attr.state_checked), checked)
        addState(intArrayOf(), idle)
    }
}

/** 成功徽章：完成后出现一次的异形，配绿色语义色，只有它允许轻微倾斜。 */
internal class PlazaSuccessBadge(context: Context) : FrameLayout(context) {
    init {
        val green = context.color(R.color.v3_green)
        val side = context.dpF(86f)
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                side * .32f, side * .32f,
                side * .44f, side * .44f,
                side * .34f, side * .34f,
                side * .44f, side * .44f,
            )
            setColor(V3Palette.withAlpha(green, .16f))
        }
        rotation = -8f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_check_24dp)
                imageTintList = ColorStateList.valueOf(green)
                rotation = 8f // 内容不倾斜：只有容器转，勾要正着读。
            },
            LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER),
        )
        layoutParams = ViewGroup.LayoutParams(context.dp(86), context.dp(86))
    }
}

/** 一行说明 + 图标的浅色提示卡，用于页面主区之前交代规则。 */
internal fun Context.noticeCard(icon: Int, text: CharSequence): LinearLayout {
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
            label(text, 13f, 400, color(R.color.v3_text_2)).apply { lineHeightRatio(1.6f) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) },
        )
    }
}

/** 正方形容器：证据照片按列宽等分，三格在 320dp 和宽屏上都保持同一比例。 */
internal class SquareFrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.EXACTLY,
        )
        super.onMeasure(side, side)
    }
}

/** 证据格的虚线「添加」底：空位要读成一个待填的槽，而不是又一个实心按钮。 */
internal fun Context.dashedSlot(radius: Int = 18): Drawable {
    val palette = V3Palette.from(this)
    return GradientDrawable().apply {
        cornerRadius = dpF(radius.toFloat())
        setColor(V3Palette.withAlpha(palette.cardFill, .6f))
        setStroke(dp(2).coerceAtLeast(2), palette.alpha30, dpF(6f), dpF(5f))
    }
}

/** 首字母头像：屏蔽名单没有头像接口，用主题浅底加首字承担识别位。 */
internal class MonogramView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = context.v3Font(600)
        color = V3Palette.from(context).textAccent
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = V3Palette.from(context).alpha15
    }
    private var letter: String = ""

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setLetter(value: CharSequence) {
        letter = value.toString().trim().take(1).uppercase()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = minOf(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, r, fill)
        if (letter.isEmpty()) return
        paint.textSize = r * .9f
        val metrics = paint.fontMetrics
        canvas.drawText(letter, width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    }
}

/** 末端小号胶囊动作：行内用，仍保证 48dp 热区。 */
internal fun Context.compactPill(text: CharSequence, action: () -> Unit): TextView =
    pillButton(text, primary = false, action = action).apply {
        textSize = 13f
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
