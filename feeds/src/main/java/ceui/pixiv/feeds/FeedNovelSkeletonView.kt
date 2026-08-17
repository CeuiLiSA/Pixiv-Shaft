package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 竖向小说列表首屏骨架图：一行行画成主力小说卡（`recy_novel`）的样子——
 * 左封面 + 右侧标题/系列/头像·作者·日期，下面一片标签 chip。shimmer / 动画生命周期在
 * [FeedSkeletonView]。
 *
 * 尺寸全部照抄 `recy_novel` + 列表的 `LinearItemDecoration(12dp)`，骨架和真卡逐像素对得上：
 * 卡左右各 12dp、卡内 padding 14dp、封面 90x134dp(圆角 12dp)、右列距封面 14dp、
 * 爱心 36dp、头像 26dp。这样首屏从骨架换成真卡时内容不跳位。
 *
 * 标签行数按固定 pattern 循环（真卡是 1~5 行不等）：确定性 → 零 jitter，尺寸不变不重算。
 */
class FeedNovelSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    // ── recy_novel 的真实尺寸 ────────────────────────────────────
    private val side = 12f * density        // LinearItemDecoration:卡左右 + 卡间距
    private val pad = 14f * density         // 卡内 padding
    private val coverW = 90f * density
    private val coverH = 134f * density
    private val coverCorner = 12f * density
    private val colGap = 14f * density      // 封面 → 右列
    private val likeSize = 36f * density
    private val avatarSize = 26f * density
    private val tagTopGap = 12f * density   // 上半 → 标签流

    private val lineCorner = 4f * density
    private val chipH = 24f * density
    private val chipGap = 8f * density

    /** 每张卡的标签行数(真卡 1~5 行不等)。 */
    private val tagRowPattern = intArrayOf(2, 3, 2, 4, 3, 2)

    /** chip 宽度占内容宽的比例，按序循环填进每一行，放不下就换行。 */
    private val chipRatios = floatArrayOf(0.30f, 0.42f, 0.24f, 0.36f, 0.28f, 0.5f, 0.33f, 0.22f)

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val cardW = w - side * 2
        if (cardW <= 0f) return
        val cardLeft = side
        val contentLeft = cardLeft + pad + coverW + colGap
        val contentRight = cardLeft + cardW - pad
        val contentW = contentRight - contentLeft
        val tagW = cardW - pad * 2
        if (contentW <= 0f || tagW <= 0f) return

        var cardTop = side
        var cardIdx = 0
        var chipIdx = 0
        while (cardTop < h) {
            val tagRows = tagRowPattern[cardIdx % tagRowPattern.size]
            val cardH = pad + coverH + tagTopGap +
                tagRows * chipH + (tagRows - 1) * chipGap + pad
            val top = cardTop + pad

            // 封面(左)
            out.add(block(cardLeft + pad, top, coverW, coverH, coverCorner))

            // 首行:标题(两行,第二行短) + 右侧爱心
            val likeLeft = contentRight - likeSize
            out.add(block(likeLeft, top, likeSize, likeSize, 8f * density))
            val titleW = likeLeft - 4f * density - contentLeft
            out.add(block(contentLeft, top + 2f * density, titleW, 15f * density, lineCorner))
            out.add(block(contentLeft, top + 23f * density, titleW * 0.62f, 15f * density, lineCorner))

            // 系列(标题下)
            out.add(block(contentLeft, top + 47f * density, contentW * 0.55f, 12f * density, lineCorner))

            // 作者行:贴封面底部(头像 + 作者名 + 日期)
            val authorTop = top + coverH - avatarSize
            out.add(block(contentLeft, authorTop, avatarSize, avatarSize, avatarSize / 2f))
            val authorLeft = contentLeft + avatarSize + 8f * density
            out.add(
                block(
                    authorLeft, authorTop + 7f * density,
                    contentW * 0.34f, 12f * density, lineCorner,
                ),
            )
            val dateW = contentW * 0.24f
            out.add(
                block(
                    contentRight - dateW, authorTop + 7f * density,
                    dateW, 12f * density, lineCorner,
                ),
            )

            // 标签流
            var rowTop = top + coverH + tagTopGap
            repeat(tagRows) {
                var x = cardLeft + pad
                while (true) {
                    val cw = tagW * chipRatios[chipIdx % chipRatios.size]
                    if (x + cw > cardLeft + pad + tagW) break
                    out.add(block(x, rowTop, cw, chipH, chipH / 2f))
                    chipIdx++
                    x += cw + chipGap
                }
                rowTop += chipH + chipGap
            }

            cardTop += cardH + side
            cardIdx++
        }
    }
}
