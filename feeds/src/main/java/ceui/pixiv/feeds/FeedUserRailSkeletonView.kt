package ceui.pixiv.feeds

import android.content.Context
import android.util.AttributeSet
import kotlin.math.roundToInt

/**
 * 「推荐用户」横向货架（`recy_user_preview_horizontal`）的首屏骨架图：一排窄卡，每张画一个圆头像 +
 * 一条名字（与竖版小说骨架同思路——画卡里的内容，不画卡本身那块底）。
 * shimmer / 动画生命周期在 [FeedSkeletonView]。
 *
 * 尺寸照抄真卡 + `LinearItemHorizontalDecoration(12dp)`：首卡左 / 顶各空 12dp，卡 85dp 宽、
 * 卡间 12dp，头像 56dp（圆）距卡顶 12dp、水平居中，名字条在头像下 8dp。
 */
class FeedUserRailSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    // RecyclerView decoration 与 XML dp 尺寸都会先取整成 px；逐卡相加可避免横向漂移。
    private fun px(dp: Int): Float = (dp * density).roundToInt().toFloat()

    private val cardW = px(85)
    private val gap = px(12)
    private val avatar = px(56)
    private val topPad = px(12)
    private val nameTop = px(8)
    private val nameH = px(13)
    private val nameW = px(50)
    private val lineCorner = px(4)

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        var left = gap // 首卡的 decoration.left
        val avatarTop = gap + topPad // decoration.top + 头像在卡内的 layout_marginTop
        while (left < w) {
            // 头像：圆（corner = 半径）
            out.add(block(left + (cardW - avatar) / 2f, avatarTop, avatar, avatar, avatar / 2f))
            // 名字条
            out.add(
                block(
                    left + (cardW - nameW) / 2f, avatarTop + avatar + nameTop,
                    nameW, nameH, lineCorner,
                ),
            )
            left += cardW + gap
        }
    }
}
