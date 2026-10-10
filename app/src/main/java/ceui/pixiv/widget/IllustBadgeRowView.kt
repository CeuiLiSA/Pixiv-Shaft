package ceui.pixiv.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import ceui.lisa.R

/**
 * 瀑布流卡片的角标行（站长推荐 score / R-18 / 页数 / W / GIF / AI / NEW）。
 *
 * 原本是「横排 LinearLayout + 7 个 TextView」，每张卡都无条件建一遍 —— 而典型作品
 * （单页、非动图、非 R-18、非 AI、非相关、非推荐列表）一个角标都不显示。
 * 7 个 TextView 的构造实测占整卡 inflate 的近一半（~2ms/个）。
 *
 * 为什么不能用 ViewStub 延后：显不显示要等 `onBind` 才知道，而 bind 发生在 layout 帧里 ——
 * stub 化只是把开销从 create 挪进 layout 帧，反而更糟。所以收成一个自绘 View：
 * 构造只剩 1 个 View，显示内容由 [setBadges] 一次性给，绘制是纯 Canvas。
 *
 * 视觉逐项照抄原布局：
 * - 标签胶囊 = `tag_stroke`（#40000000、2dp 圆角）+ 白字 11sp Montserrat ExtraBold
 *   （`@style/textBlack`）+ 左右 4dp / 上下 2dp 内边距 + 相互 6dp 间距；
 * - 站长推荐 pill = `bg_trending_score_pill`（315 度线性渐变 #CC000000 -> 透明、4dp 圆角）
 *   + `trending_score_amber` 字 + 左 6dp / 右 8dp / 上下 2dp 内边距；
 * - 行内边距 6dp。
 */
class IllustBadgeRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 一个角标。[amber] = 站长推荐那支（另一套底色 / 字色 / 内边距）。 */
    data class Badge(val text: String, val amber: Boolean = false)

    private val density = resources.displayMetrics.density
    // 11sp -> px。别用 DisplayMetrics.scaledDensity（API 34 起 deprecated）。
    private val textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        11f,
        resources.displayMetrics,
    )

    private val rowPadding = 6f * density
    private val gap = 6f * density
    private val tagPadH = 4f * density
    private val tagPadV = 2f * density
    private val tagRadius = 2f * density
    private val pillPadStart = 6f * density
    private val pillPadEnd = 8f * density

    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = textSizePx
    }
    /**
     * 站长推荐 pill 的背景：直接用原 drawable，而不是手搓渐变 —— 渐变方向（angle=315）在
     * Canvas 里要自己推坐标，容易和原效果镜像，用真 drawable 就逐像素一致、也不用猜。
     * 懒加载：只有真出现站长推荐角标时才取，普通列表不付这笔钱。
     */
    private var pillBackground: Drawable? = null
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 站长推荐 pill 用的是**系统默认字体的粗体**，不是 Montserrat：原 style `TrendingScorePill`
        // 只写了 `textStyle="bold"`、没有 `fontFamily`（`fontFamily=montserrat_extra_bold` 是标签
        // 那套 `textBlack` 才有的）。套 Montserrat 会明显更粗，而且 `▲` 会落到 Montserrat 缺字
        // 回退出来的字形上（明显偏小）。
        typeface = Typeface.DEFAULT_BOLD
        // 必须是 8 位 ARGB。`0xFFD54F` 只有 6 位 —— 作为 Int 是 0x00FFD54F，alpha = 0，
        // 字会完全透明（XML 里 `#FFD54F` 隐含 alpha=FF，Kotlin 字面量不会）。
        color = 0xFFFFD54F.toInt()
        textSize = textSizePx
        // 原 style 还有一层很淡的投影：shadowColor=#000000 / dx=0 / dy=1 / radius=2（裸像素值）。
        setShadowLayer(2f, 0f, 1f, 0xFF000000.toInt())
    }

    /** 两种字体各自的字形上下界：在 [init] 里按各自 typeface 量一次，绘制时用来算 baseline。 */
    private val tagAscent: Float
    private val tagDescent: Float
    private val pillAscent: Float
    private val pillDescent: Float

    private val rect = RectF()
    private var badges: List<Badge> = emptyList()

    /** 每个角标的宽度（不含 gap），[onMeasure] 与 [onDraw] 共用。 */
    private val widths = ArrayList<Float>()

    /** 角标高度（同一行等高）。 */
    private val badgeHeight: Float

    init {
        // 字体资源在 witstudio 模块（非传递 R，必须全限定引用该模块的 R）。
        val extraBold = ResourcesCompat.getFont(context, ceui.pixiv.witstudio.R.font.montserrat_extra_bold)
            ?: Typeface.DEFAULT_BOLD
        tagText.typeface = extraBold
        tagAscent = tagText.fontMetrics.ascent
        tagDescent = tagText.fontMetrics.descent
        pillAscent = pillText.fontMetrics.ascent
        pillDescent = pillText.fontMetrics.descent
        badgeHeight = 2f * tagPadV + (tagDescent - tagAscent)
    }

    /**
     * 设置要显示的角标，顺序即绘制顺序（从左到右）。空列表 = 画不出任何东西
     * （尺寸 0，且 [onDraw] 直接返回）；**不动 visibility** —— 屏蔽态那把整行藏掉是
     * `applyIllustSpoilerMask` 的职责，这里插手会打架。
     */
    fun setBadges(list: List<Badge>) {
        badges = list
        widths.clear()
        for (b in list) {
            val paint = if (b.amber) pillText else tagText
            val padH = if (b.amber) pillPadStart + pillPadEnd else 2f * tagPadH
            widths.add(padH + paint.measureText(b.text))
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentW = if (badges.isEmpty()) {
            0f
        } else {
            2f * rowPadding + widths.sum() + gap * (badges.size - 1)
        }
        val contentH = if (badges.isEmpty()) 0f else 2f * rowPadding + badgeHeight
        setMeasuredDimension(
            resolveSize(contentW.toInt(), widthMeasureSpec),
            resolveSize(contentH.toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (badges.isEmpty()) return
        val top = rowPadding
        var x = rowPadding
        for ((i, badge) in badges.withIndex()) {
            val w = widths[i]
            rect.set(x, top, x + w, top + badgeHeight)
            val padStart: Float
            val paint: Paint
            if (badge.amber) {
                val bg = pillBackground ?: ContextCompat
                    .getDrawable(context, R.drawable.bg_trending_score_pill)
                    ?.also { pillBackground = it }
                    ?: ColorDrawable(0xCC000000.toInt()).also { pillBackground = it }
                bg.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                bg.draw(canvas)
                padStart = pillPadStart
                paint = pillText
            } else {
                canvas.drawRoundRect(rect, tagRadius, tagRadius, tagBg)
                padStart = tagPadH
                paint = tagText
            }
            val ascent = if (badge.amber) pillAscent else tagAscent
            val descent = if (badge.amber) pillDescent else tagDescent
            // 与原 TextView（includeFontPadding=false）一致的垂直居中：按字形高居中再回推 baseline。
            val baseline = rect.top + (badgeHeight - (descent - ascent)) / 2f - ascent
            canvas.drawText(badge.text, x + padStart, baseline, paint)
            x += w + gap
        }
    }
}