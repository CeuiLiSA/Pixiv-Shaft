package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 竖向「特辑」列表（pixivision，`cell_pivision`）的首屏骨架图：封面大图 + 下面一行日期/CTA。
 * shimmer / 动画生命周期在 [FeedSkeletonView]。
 *
 * 尺寸照抄 `cell_pivision` + 列表的 `LinearItemDecoration(16dp)`（卡本身不带 margin，左右/卡间距
 * 全由 decoration 给）：卡左右 16dp、卡间 16dp、圆角 28dp、封面 200dp，内容区 paddingH 16dp /
 * 上 12dp / 下 14dp。骨架换成真卡时不跳位。
 */
class FeedArticleSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val gap = 16f * density          // LinearItemDecoration:卡左右 + 卡间距
    private val cardCorner = 28f * density
    private val coverH = 200f * density
    private val padH = 16f * density
    private val padTop = 12f * density
    private val padBottom = 14f * density
    private val lineH = 12f * density
    private val lineCorner = 4f * density

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val cardW = w - gap * 2
        if (cardW <= 0f) return
        val contentW = cardW - padH * 2
        if (contentW <= 0f) return
        val cardH = coverH + padTop + lineH + padBottom

        var cardTop = gap
        while (cardTop < h) {
            // 封面大图(卡的主体,圆角同卡)
            out.add(block(gap, cardTop, cardW, coverH, cardCorner))
            // 内容区:左日期 + 右 CTA
            val lineTop = cardTop + coverH + padTop
            out.add(block(gap + padH, lineTop, contentW * 0.3f, lineH, lineCorner))
            val ctaW = contentW * 0.22f
            out.add(block(gap + padH + contentW - ctaW, lineTop, ctaW, lineH, lineCorner))

            cardTop += cardH + gap
        }
    }
}
