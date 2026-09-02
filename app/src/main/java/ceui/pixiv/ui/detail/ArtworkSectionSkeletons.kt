package ceui.pixiv.ui.detail

import android.content.Context
import android.util.AttributeSet
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock

/**
 * 评论预览区块的骨架图。相关作品那块直接复用瀑布流骨架
 * [ceui.pixiv.feeds.FeedStaggeredSkeletonView]（相关卡就是普通瀑布流卡）。
 *
 * 口径跟 [ceui.pixiv.feeds.FeedUserRailSkeletonView] 一致：**只画条目里的内容**。评论行本身
 * 是无界平铺（#1038）、没有卡底，所以骨架也就没有底块可画。尺寸逐项照抄真实布局，数据到位时
 * 骨架和真内容几乎原地替换，不会跳。shimmer / 动画生命周期全在 [FeedSkeletonView]。
 */
class SectionCommentsSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    // 逐项对齐 cell_comment_preview.xml（无界平铺行：左右 12dp、上下各 12dp）
    private val rowPadH = 12f * density
    private val rowPadV = 12f * density
    private val avatar = 32f * density
    private val avatarGap = 10f * density
    private val nameW = 96f * density
    private val nameH = 13f * density
    private val timeW = 76f * density
    private val timeH = 11f * density

    /** 正文相对行左边的缩进：头像那列的宽度（布局里写死的 42dp marginStart）。 */
    private val bodyIndent = 42f * density
    private val bodyTop = 6f * density
    private val lineH = 13f * density
    private val lineGap = 6f * density

    private val lineCorner = 4f * density

    /** 两行正文的一行：上下 padding + 头像行 + 正文两行。评论预览最多 3 条。 */
    private val rowH = rowPadV * 2 + avatar + bodyTop + lineH * 2 + lineGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (rowH * ROW_COUNT).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        var top = 0f
        repeat(ROW_COUNT) {
            val left = rowPadH
            val right = w - rowPadH
            // 头像（圆）
            out.add(block(left, top + rowPadV, avatar, avatar, avatar / 2f))
            // 名字条：与头像纵向居中对齐
            out.add(
                block(
                    left + avatar + avatarGap, top + rowPadV + (avatar - nameH) / 2f,
                    nameW, nameH, lineCorner,
                ),
            )
            // 时间条：贴行右内边
            out.add(
                block(
                    right - timeW, top + rowPadV + (avatar - timeH) / 2f,
                    timeW, timeH, lineCorner,
                ),
            )
            // 正文两行，第二行短一截（真实评论最后一行也很少满行）
            val bodyLeft = left + bodyIndent
            val bodyW = right - bodyLeft
            val firstLineTop = top + rowPadV + avatar + bodyTop
            out.add(block(bodyLeft, firstLineTop, bodyW, lineH, lineCorner))
            out.add(
                block(
                    bodyLeft, firstLineTop + lineH + lineGap,
                    bodyW * SECOND_LINE_RATIO, lineH, lineCorner,
                ),
            )
            top += rowH
        }
    }

    private companion object {
        /** 评论预览固定只出 3 条（fetchArtworkComments 就取前 3 条）。 */
        const val ROW_COUNT = 3
        const val SECOND_LINE_RATIO = 0.55f
    }
}

/**
 * 作者其他作品横向条的骨架图：一排正方形封面块，尺寸 / 间距 / 内边距逐项照抄
 * `section_v3_author_works` + [ceui.lisa.adapters.LAdapter]（卡边长 `(屏宽 - 48dp) / 3`、
 * 卡间 8dp、RV 左右各 12dp 内边距、卡片圆角 12dp）。因此静止时同样是「3 张整卡 + 右侧露出
 * 第 4 张的一条边」，跟真数据到位后一模一样。
 */
class SectionAuthorWorksSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val padH = 12f * density
    private val gap = 8f * density
    private val corner = 12f * density

    /** 与 LAdapter.imageSize 同式同源（都读 resources.displayMetrics），保证边长一致。 */
    private val cardSize = (resources.displayMetrics.widthPixels - 48f * density) / 3f

    /** RV 自身高度 = 卡边长 + 16dp，见 renderAuthorWorks 里对 layoutParams.height 的赋值。 */
    private val rowH = cardSize + 16f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(rowH.toInt(), heightMeasureSpec),
        )
    }

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        if (cardSize <= 0f) return
        var left = padH
        while (left < w) {
            out.add(block(left, 0f, cardSize, cardSize, corner))
            left += cardSize + gap
        }
    }
}
