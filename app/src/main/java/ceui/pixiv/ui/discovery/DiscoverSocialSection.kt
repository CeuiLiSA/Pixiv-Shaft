package ceui.pixiv.ui.discovery

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape

/** The final shelf in FragmentCenter. No data loading or independent navigation state. */
class DiscoverSocialSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val colors = DiscoverSocialColors(context)
    private val chat = SocialEntryCard(context, colors, true).apply { id = R.id.discover_chat_entry }
    private val community = SocialEntryCard(context, colors, false).apply { id = R.id.discover_community_entry }

    init {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(28), context.dp(20), 0)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.label("SHAFT / CONNECT", 10f, 700, colors.palette.textAccent).apply {
                letterSpacing = .16f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(context.label(context.getString(R.string.discover_social_title), 20f, 700).apply {
                lineHeightRatio(1.5f)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
        }
        header.addView(heading, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_discover_social_spark)
            imageTintList = ColorStateList.valueOf(colors.palette.textAccent)
            background = shape(context.dpF(15f), ColorUtils.compositeColors(colors.palette.alpha08, colors.palette.cardFill))
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            rotation = 8f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(context.dp(42), context.dp(42)).apply { marginStart = context.dp(12) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(SocialCardsLayout(context).apply {
            addView(chat)
            addView(community)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16) })
        addView(context.label("PIXIV-SHAFT", 9f, 500, colors.secondaryOn(context.color(R.color.v3_bg))).apply {
            gravity = Gravity.CENTER
            letterSpacing = .22f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, context.dp(24), 0, context.dp(8))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setEntryClickListeners(onChat: OnClickListener, onCommunity: OnClickListener) {
        chat.setOnClickListener(onChat)
        community.setOnClickListener(onCommunity)
    }
}

/** Every accent comes from the actual host palette, including custom HEX themes. */
internal class DiscoverSocialColors(private val context: Context) {
    val palette = V3Palette.from(context)
    val chatFill = ColorUtils.compositeColors(palette.alpha20, palette.cardFill)
    val ink = context.color(R.color.v3_text_1)
    val onPrimary = readable(palette.onPrimary, palette.primary)

    fun accentOn(fill: Int): Int = readable(palette.textAccent, fill)
    fun secondaryOn(fill: Int): Int = readable(context.color(R.color.v3_text_2), fill)

    private fun readable(foreground: Int, background: Int): Int {
        val opaque = ColorUtils.compositeColors(foreground, background)
        if (ColorUtils.calculateContrast(opaque, background) >= 4.5) return opaque
        val target = if (ColorUtils.calculateContrast(Color.WHITE, background) >
            ColorUtils.calculateContrast(Color.BLACK, background)) Color.WHITE else Color.BLACK
        for (step in 1..100) {
            val candidate = ColorUtils.blendARGB(opaque, target, step / 100f)
            if (ColorUtils.calculateContrast(candidate, background) >= 4.5) return candidate
        }
        return target
    }
}

/** Measure from available content width; large fonts always get one full-width card. */
private class SocialCardsLayout(context: Context) : ViewGroup(context) {
    private var columns = false
    private val gap = context.dp(12)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cards = (0 until childCount).map { getChildAt(it) as SocialEntryCard }.filter { it.visibility != GONE }
        columns = cards.size == 2 && width >= context.dp(332) && resources.configuration.fontScale <= 1.3f
        val cardWidth = if (columns) (width - gap) / 2 else width
        cards.forEach {
            it.horizontal = !columns && width >= context.dp(264) && resources.configuration.fontScale <= 1.3f
            it.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val height = if (columns) {
            val tallest = cards.maxOf { it.measuredHeight }
            cards.forEach { it.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY)) }
            tallest
        } else cards.sumOf { it.measuredHeight } + gap * (cards.size - 1).coerceAtLeast(0)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var position = 0
        for (i in 0 until childCount) {
            val card = getChildAt(i)
            if (card.visibility == GONE) continue
            val x = if (columns) {
                if (layoutDirection == LAYOUT_DIRECTION_RTL) width - position - card.measuredWidth else position
            } else 0
            val y = if (columns) 0 else position
            card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
            position += (if (columns) card.measuredWidth else card.measuredHeight) + gap
        }
    }
}

/** Text stays in native TextViews; only the decorative miniature is drawn on Canvas. */
internal class SocialEntryCard(context: Context, colors: DiscoverSocialColors, chat: Boolean) : ViewGroup(context) {
    var horizontal = false
    private val fill = if (chat) colors.chatFill else colors.palette.cardFill
    private val accent = colors.accentOn(fill)
    private val eyebrow = context.label(if (chat) "CHAT ROOM" else "COMMUNITY", 10f, 700, accent).apply { letterSpacing = .09f }
    private val art = DiscoverSocialArtView(context, colors, chat)
    private val title = context.label(context.getString(if (chat) R.string.chat_drawer_entry else R.string.discover_community_title), 20f, 700, colors.ink).apply {
        lineHeightRatio(1.4f)
    }
    private val description = context.label(context.getString(if (chat) R.string.discover_chat_description else R.string.discover_community_description), 12f, 400, colors.secondaryOn(fill)).apply {
        lineHeightRatio(1.7f)
    }
    private val actionLabel = context.label(context.getString(if (chat) R.string.discover_chat_action else R.string.discover_community_action), 12f, 600, accent).apply {
        lineHeightRatio(1.5f)
    }
    private val action = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(actionLabel, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply {
            val arrowFill = if (chat) colors.palette.primary else ColorUtils.compositeColors(colors.palette.alpha20, fill)
            setImageResource(R.drawable.baseline_arrow_forward_24)
            imageTintList = ColorStateList.valueOf(if (chat) colors.onPrimary else colors.accentOn(arrowFill))
            background = shape(context.dpF(16f), arrowFill)
            setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(7))
        }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { marginStart = context.dp(8) })
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.palette.cardHairline
        strokeWidth = context.dpF(.5f).coerceAtLeast(1f)
    }
    private var textWidth = 0
    private var dividerY = 0f

    init {
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(14))
        background = context.ripple(
            shape(context.dpF(22f), fill, if (chat) null else colors.palette.cardHairline, context.dp(1).coerceAtLeast(1)),
            shape(context.dpF(22f), Color.WHITE),
        )
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(48)
        contentDescription = listOf(title.text, description.text, actionLabel.text).joinToString("，")
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        listOf(eyebrow, art, title, description, action).forEach {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(it)
        }
        setWillNotDraw(false)
        pressScale()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val artWidth = if (horizontal) minOf(context.dp(132), (innerWidth * .44f).toInt()) else minOf(context.dp(142), innerWidth)
        textWidth = if (horizontal) (innerWidth - artWidth - context.dp(12)).coerceAtLeast(0) else innerWidth
        val textSpec = MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        listOf(eyebrow, title, description, action).forEach { it.measure(textSpec, unspecified) }
        art.measure(MeasureSpec.makeMeasureSpec(artWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(artWidth * 140 / 142, MeasureSpec.EXACTLY))
        val textHeight = eyebrow.measuredHeight + context.dp(if (horizontal) 12 else 4) +
            title.measuredHeight + context.dp(6) + description.measuredHeight + context.dp(26) + action.measuredHeight
        val contentHeight = if (horizontal) maxOf(textHeight, art.measuredHeight) else textHeight + art.measuredHeight + context.dp(4)
        setMeasuredDimension(width, resolveSize(paddingTop + contentHeight + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val textLeft = if (rtl) width - paddingRight - textWidth else paddingLeft
        var y = paddingTop
        fun placeText(view: View) {
            view.layout(textLeft, y, textLeft + view.measuredWidth, y + view.measuredHeight)
            y += view.measuredHeight
        }
        placeText(eyebrow)
        if (horizontal) {
            val x = if (rtl) paddingLeft else width - paddingRight - art.measuredWidth
            val artTop = paddingTop + (height - paddingTop - paddingBottom - art.measuredHeight) / 2
            art.layout(x, artTop, x + art.measuredWidth, artTop + art.measuredHeight)
            y += context.dp(12)
        } else {
            y += context.dp(4)
            val x = paddingLeft + (width - paddingLeft - paddingRight - art.measuredWidth) / 2
            art.layout(x, y, x + art.measuredWidth, y + art.measuredHeight)
            y += art.measuredHeight + context.dp(4)
        }
        placeText(title)
        y += context.dp(6)
        placeText(description)
        // Equal-height columns keep the actions aligned even when one title wraps.
        val actionTop = maxOf(y + context.dp(26), height - paddingBottom - action.measuredHeight)
        dividerY = (actionTop - context.dp(12)).toFloat()
        action.layout(textLeft, actionTop, textLeft + textWidth, actionTop + action.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - textWidth else paddingLeft
        canvas.drawLine(x.toFloat(), dividerY, (x + textWidth).toFloat(), dividerY, dividerPaint)
    }
}
