package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 竖向小说列表首屏骨架图：一行行画成主力小说条目（`recy_novel`）的样子——
 * 左封面 + 右侧标题/系列/头像·作者·日期，下面一片标签 chip。shimmer / 动画生命周期在
 * [FeedSkeletonView]。
 *
 * 尺寸全部照抄 `recy_novel`（#1038 起为无界平铺，列表无 ItemDecoration），骨架和真条目
 * 逐像素对得上：条目左右 padding 16dp、上下 padding 12dp、封面 80x119dp(圆角 12dp)、
 * 右列距封面 12dp、爱心 36dp、头像 22dp。这样首屏从骨架换成真条目时内容不跳位。
 *
 * 标签行数按固定 pattern 循环（真条目去译名后多为 1~2 行）：确定性 → 零 jitter，尺寸不变不重算。
 */
class FeedNovelSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    // ── recy_novel 的真实尺寸 ────────────────────────────────────
    private val padH = 16f * density        // 条目左右 padding
    private val padV = 12f * density        // 条目上下 padding
    private val coverW = 80f * density
    private val coverH = 119f * density
    private val coverCorner = 12f * density
    private val colGap = 12f * density      // 封面 → 右列
    private val likeSize = 36f * density
    private val avatarSize = 22f * density
    private val tagTopGap = 10f * density   // 上半 → 标签流

    private val lineCorner = 4f * density
    private val chipH = 24f * density
    private val chipGap = 6f * density

    /** 每个条目的标签行数(真条目去译名后多为 1~2 行)。 */
    private val tagRowPattern = intArrayOf(1, 2, 1, 2, 2, 1)

    /** chip 宽度占内容宽的比例，按序循环填进每一行，放不下就换行。 */
    private val chipRatios = floatArrayOf(0.18f, 0.26f, 0.15f, 0.22f, 0.19f, 0.30f, 0.21f, 0.16f)

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val contentLeft = padH + coverW + colGap
        val contentRight = w - padH
        val contentW = contentRight - contentLeft
        val tagW = w - padH * 2
        if (contentW <= 0f || tagW <= 0f) return

        var itemTop = 0f
        var itemIdx = 0
        var chipIdx = 0
        while (itemTop < h) {
            val tagRows = tagRowPattern[itemIdx % tagRowPattern.size]
            val itemH = padV + coverH + tagTopGap +
                tagRows * chipH + (tagRows - 1) * chipGap + padV
            val top = itemTop + padV

            // 封面(左)
            out.add(block(padH, top, coverW, coverH, coverCorner))

            // 首行:标题(两行,第二行短) + 右侧爱心
            val likeLeft = contentRight - likeSize
            out.add(block(likeLeft, top, likeSize, likeSize, 8f * density))
            val titleW = likeLeft - 4f * density - contentLeft
            out.add(block(contentLeft, top + 2f * density, titleW, 14f * density, lineCorner))
            out.add(block(contentLeft, top + 22f * density, titleW * 0.62f, 14f * density, lineCorner))

            // 系列(标题下)
            out.add(block(contentLeft, top + 45f * density, contentW * 0.5f, 12f * density, lineCorner))

            // 作者行:紧随系列之下(头像 + 作者名 + 日期)
            val authorTop = top + 65f * density
            out.add(block(contentLeft, authorTop, avatarSize, avatarSize, avatarSize / 2f))
            val authorLeft = contentLeft + avatarSize + 7f * density
            out.add(
                block(
                    authorLeft, authorTop + 5f * density,
                    contentW * 0.34f, 12f * density, lineCorner,
                ),
            )
            val dateW = contentW * 0.24f
            out.add(
                block(
                    contentRight - dateW, authorTop + 5f * density,
                    dateW, 12f * density, lineCorner,
                ),
            )

            // 标签流
            var rowTop = top + coverH + tagTopGap
            repeat(tagRows) {
                var x = padH
                while (true) {
                    val cw = tagW * chipRatios[chipIdx % chipRatios.size]
                    if (x + cw > padH + tagW) break
                    out.add(block(x, rowTop, cw, chipH, chipH / 2f))
                    chipIdx++
                    x += cw + chipGap
                }
                rowTop += chipH + chipGap
            }

            itemTop += itemH
            itemIdx++
        }
    }
}
