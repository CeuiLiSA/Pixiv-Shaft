package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 发现页「pixivision 特辑」横向货架（`recy_artical_horizon`）的首屏骨架图：一排整卡大小的圆角块。
 * shimmer / 动画生命周期在 [FeedSkeletonView]。
 *
 * 尺寸照抄真卡 + 货架的 padding/decoration：卡 220x160dp、20dp 圆角（= Renderer 注入
 * settingsCardBg 的半径）、卡间 12dp、首卡左缘 20dp（和发现页所有货架、标题对齐同一条竖线）。
 * 整卡就是一张图（标题压在图上），所以骨架就是整块，不再拆标题条。
 */
class FeedArticleRailSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val startPad = 20f * density   // 货架 paddingStart
    private val gap = 12f * density        // HorizontalSpaceDecoration
    private val cardW = 220f * density
    private val cardH = 160f * density     // = R.dimen.article_horizontal_height
    private val corner = 20f * density      // = Renderer 注入的 settingsCardBg 半径

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        // 货架高度就是卡高；容器万一更矮（宿主改了高度）就压着画，别溢出。
        val blockH = minOf(cardH, h)
        var left = startPad
        while (left < w) {
            out.add(block(left, 0f, cardW, blockH, corner))
            left += cardW + gap
        }
    }
}
