package ceui.pixiv.ui.common

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock

/**
 * 用户卡列表（`recy_user_preview` / [UserFeedFragment]）的列表几何，卡片与骨架共用这一份：
 * 左右 16dp、首张卡上方 16dp、卡间 12dp；宽屏把整列收到 720dp 居中（同广场、版本历史），
 * 否则三张 1:1 预览图会把一张卡撑成半屏高的横幅。
 */
internal object UserCardMetrics {
    const val GUTTER_DP = 16
    const val TOP_DP = 16
    const val GAP_DP = 12
    const val MAX_WIDTH_DP = 720

    /** 内容列距左右边缘的距离：窄屏是 16dp，宽屏按 720dp 列居中。 */
    fun sideInset(availableWidth: Int, density: Float): Int {
        val gutter = (GUTTER_DP * density).toInt()
        val maxWidth = (MAX_WIDTH_DP * density).toInt()
        return maxOf(gutter, (availableWidth - maxWidth) / 2)
    }
}

/** 按 [UserCardMetrics] 给每张卡留边；分页底部的加载 / 重试条也在同一列里。 */
internal class UserCardDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val top = (UserCardMetrics.TOP_DP * density).toInt()
    private val gap = (UserCardMetrics.GAP_DP * density).toInt()

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val side = UserCardMetrics.sideInset(parent.width - parent.paddingLeft - parent.paddingRight, density)
        outRect.left = side
        outRect.right = side
        outRect.top = if (parent.getChildAdapterPosition(view) == 0) top else 0
        outRect.bottom = gap
    }
}

/**
 * 用户卡列表的首屏骨架：照真卡的几何画卡里的内容（不画卡底，同广场 / 小说骨架）——
 * 三张 1:1 预览格（格间 2dp）→ 44dp 圆头像 + 名字条 + 账号条 → 末端 36dp 关注胶囊。
 * 改 `recy_user_preview.xml` 的尺寸时同步改这里，真卡才能几乎原位替换骨架。
 */
internal class UserCardSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val top = UserCardMetrics.TOP_DP * density
    private val gap = UserCardMetrics.GAP_DP * density
    private val seam = 2f * density
    private val tileCorner = 8f * density
    private val pad = 16f * density
    private val rowPadV = 10f * density
    private val rowH = 48f * density
    private val avatar = 44f * density
    private val textGap = 12f * density
    private val nameW = 120f * density
    private val nameH = 14f * density
    private val accountW = 80f * density
    private val accountH = 11f * density
    private val pillW = 76f * density
    private val pillH = 36f * density
    private val lineCorner = 4f * density

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val side = UserCardMetrics.sideInset(w.toInt(), density).toFloat()
        val left = side
        val cardW = w - side * 2f
        if (cardW <= pad * 2f) return
        val tile = (cardW - seam * 2f) / 3f
        val cardH = tile + rowPadV * 2f + rowH
        var y = top
        while (y < h) {
            repeat(3) { i ->
                out.add(block(left + i * (tile + seam), y, tile, tile, tileCorner))
            }
            val rowTop = y + tile + rowPadV
            val avatarTop = rowTop + (rowH - avatar) / 2f
            out.add(block(left + pad, avatarTop, avatar, avatar, avatar / 2f))
            val textLeft = left + pad + avatar + textGap
            out.add(block(textLeft, avatarTop + 5f * density, nameW, nameH, lineCorner))
            out.add(block(textLeft, avatarTop + 26f * density, accountW, accountH, lineCorner))
            out.add(
                block(
                    left + cardW - pad - pillW, rowTop + (rowH - pillH) / 2f,
                    pillW, pillH, pillH / 2f,
                ),
            )
            y += cardH + gap
        }
    }
}
