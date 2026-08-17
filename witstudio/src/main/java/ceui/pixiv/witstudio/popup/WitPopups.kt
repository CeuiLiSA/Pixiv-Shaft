package ceui.pixiv.witstudio.popup

import android.content.Context
import android.widget.AdapterView
import android.widget.ListAdapter
import android.widget.ListView

/** [WitPopup] 的工厂，替代 `QMUIPopups`。 */
public object WitPopups {

    /** 自定义内容的弹出层。链上再调 `.view(...)` 塞内容。 */
    @JvmStatic
    public fun popup(context: Context): WitPopup = WitPopup(context)

    /**
     * 列表弹出层：固定 [width]、限高 [maxHeight]，内容是一个吃 [adapter] 的 [ListView]。
     *
     * 分隔线刻意去掉（`divider = null`）：V3 的列表靠间距和圆角分组，不画横线。
     */
    @JvmStatic
    public fun listPopup(
        context: Context,
        width: Int,
        maxHeight: Int,
        adapter: ListAdapter,
        listener: AdapterView.OnItemClickListener,
    ): WitPopup {
        val listView = ListView(context).apply {
            this.adapter = adapter
            onItemClickListener = listener
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            // 圆角容器已经 clipToOutline，这里再让滑动边缘发光不越界。
            overScrollMode = ListView.OVER_SCROLL_NEVER
        }
        return WitPopup(context)
            .view(listView)
            .contentSize(width, maxHeight)
    }
}
