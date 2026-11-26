package ceui.pixiv.ui.pinned

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 「我置顶的内容」三个列表共用的 V3 几何规则：
 * 手机端左右 16dp、首卡上方 12dp、卡间 12dp；宽屏列宽上限 720dp 居中
 * （对齐 `docs/v3-design-philosophy.md` 与 `UserCardMetrics`）。
 */
internal object PinnedListMetrics {
    const val GUTTER_DP = 16
    const val TOP_DP = 12
    const val GAP_DP = 12
    const val MAX_WIDTH_DP = 720

    fun sideInset(availableWidth: Int, density: Float): Int {
        val gutter = (GUTTER_DP * density).toInt()
        val maxWidth = (MAX_WIDTH_DP * density).toInt()
        return maxOf(gutter, (availableWidth - maxWidth) / 2)
    }
}

internal class PinnedListDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val top = (PinnedListMetrics.TOP_DP * density).toInt()
    private val gap = (PinnedListMetrics.GAP_DP * density).toInt()

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val available = parent.width - parent.paddingLeft - parent.paddingRight
        val side = PinnedListMetrics.sideInset(available, density)
        outRect.left = side
        outRect.right = side
        outRect.top = if (parent.getChildAdapterPosition(view) == 0) top else 0
        outRect.bottom = gap
    }
}
