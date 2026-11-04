package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 「动态」页时间线模式（`recy_timeline_illust`，单列大卡）的首屏骨架图：
 * 圆头像 + 画师名/时间两条 → 标题条 → 一整块大图。shimmer / 动画生命周期在 [FeedSkeletonView]。
 *
 * 尺寸照抄真卡（2026-11-04 V3 版）：卡片内边距左右 16dp / 上下 14dp、头像 40dp、头像到标题 12dp、
 * 标题一行约 22dp、标题到图区 10dp、图区圆角 16dp，这样首屏从骨架换成真卡时内容不跳位。
 *
 * 图高按固定 pattern 在 0.6~1.5 倍宽之间循环（真卡就是把作品宽高比钳在这个区间），
 * 确定性 → 零 jitter，尺寸不变不重算。
 */
class FeedTimelineSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    // ── recy_timeline_illust 的真实尺寸 ────────────────────────────
    private val sideMargin = 16f * density      // 卡片左右内边距
    private val headerPadTop = 14f * density    // 卡片上内边距
    private val headerPadBottom = 12f * density // 头像 → 标题
    private val avatar = 40f * density
    private val avatarTextGap = 12f * density   // 头像 → 名字列
    private val titleH = 16f * density          // 标题条本身
    private val titleLineH = 22f * density      // 标题一行实际占高（16sp）
    private val titlePadBottom = 10f * density  // 标题 → 图区
    private val imageCorner = 16f * density
    private val cardPadBottom = 14f * density   // 卡片下内边距

    private val lineCorner = 4f * density

    /** 每张卡的图高比例（真卡钳制区间内取几档，模拟长短不一的作品）。 */
    private val ratioPattern = floatArrayOf(1.4f, 0.75f, 1.5f, 1.0f, 0.62f)

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val imageW = w - sideMargin * 2
        if (imageW <= 0f) return

        var cardTop = 0f
        var cardIdx = 0
        while (cardTop < h) {
            val imageH = imageW * ratioPattern[cardIdx % ratioPattern.size]

            // 头像 + 画师名 / 时间
            val headerTop = cardTop + headerPadTop
            out.add(block(sideMargin, headerTop, avatar, avatar, avatar / 2f))
            val textLeft = sideMargin + avatar + avatarTextGap
            val nameW = (w - textLeft - sideMargin) * 0.42f
            out.add(block(textLeft, headerTop + 6f * density, nameW, 14f * density, lineCorner))
            out.add(
                block(
                    textLeft, headerTop + 24f * density,
                    nameW * 0.6f, 12f * density, lineCorner,
                ),
            )

            // 标题条
            val titleTop = headerTop + avatar + headerPadBottom
            out.add(block(sideMargin, titleTop + (titleLineH - titleH) / 2f, imageW * 0.68f, titleH, lineCorner))

            // 大图
            val imageTop = titleTop + titleLineH + titlePadBottom
            out.add(block(sideMargin, imageTop, imageW, imageH, imageCorner))

            cardTop = imageTop + imageH + cardPadBottom
            cardIdx++
        }
    }
}
