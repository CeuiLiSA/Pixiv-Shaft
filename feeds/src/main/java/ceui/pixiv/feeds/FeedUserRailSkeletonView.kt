package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet

/**
 * 「推荐用户」横向货架（`recy_user_preview_horizontal`）的首屏骨架图：一排窄卡，每张画一个圆头像 +
 * 一条名字（与竖版小说骨架同思路——画卡里的内容，不画卡本身那块底）。
 * shimmer / 动画生命周期在 [FeedSkeletonView]。
 *
 * 尺寸照抄真卡 + `LinearItemHorizontalDecoration(12dp)`：卡 85dp 宽、卡间 12dp，头像 56dp（圆）
 * 距卡顶 12dp、水平居中，名字条在头像下 8dp。
 */
class FeedUserRailSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val cardW = 85f * density
    private val gap = 12f * density
    private val avatar = 56f * density
    private val topPad = 12f * density
    private val nameTop = 8f * density
    private val nameH = 13f * density
    private val nameW = 50f * density
    private val lineCorner = 4f * density

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        var left = 0f
        while (left < w) {
            // 头像：圆（corner = 半径）
            out.add(block(left + (cardW - avatar) / 2f, topPad, avatar, avatar, avatar / 2f))
            // 名字条
            out.add(
                block(
                    left + (cardW - nameW) / 2f, topPad + avatar + nameTop,
                    nameW, nameH, lineCorner,
                ),
            )
            left += cardW + gap
        }
    }
}
