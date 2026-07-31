package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * 支持 `android:maxHeight` 的 [NestedScrollView]。
 *
 * 原生 NestedScrollView（FrameLayout 系）不消费 `android:maxHeight`，XML 里写了会被
 * 静默忽略 —— 「wrap_content 但封顶、超出内滚」这种形态原生做不出来。本类在 onMeasure
 * 里把 heightSpec 收紧到 maxHeight（AT_MOST），内容不足封顶时仍是 wrap_content 的自然高度。
 *
 * 首个使用方：画师主页 tag 筛选条展开态（PR #947）—— 折叠 2 行时高度自然，
 * 全量展开时封顶、内部滚动，不把下方作品列表挤没。
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val maxHeightPx: Int = run {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        try {
            a.getDimensionPixelSize(0, NO_LIMIT)
        } finally {
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var hSpec = heightMeasureSpec
        if (maxHeightPx != NO_LIMIT) {
            hSpec = when (MeasureSpec.getMode(heightMeasureSpec)) {
                // EXACTLY 是外部布局的明确指令（match_parent / 固定值），不越权改写。
                MeasureSpec.EXACTLY -> heightMeasureSpec
                MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(
                    minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx),
                    MeasureSpec.AT_MOST,
                )
                else -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            }
        }
        super.onMeasure(widthMeasureSpec, hSpec)
    }

    companion object {
        private const val NO_LIMIT = -1
    }
}
