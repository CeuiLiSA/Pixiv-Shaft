package ceui.pixiv.ui.notification

import androidx.core.view.isVisible
import ceui.lisa.databinding.CellInfoCategoryHeaderBinding
import ceui.lisa.databinding.CellInfoEntryBinding
import ceui.pixiv.api.model.CategorizedInfo
import ceui.pixiv.api.model.InfoItem
import ceui.pixiv.feeds.FeedItem

/**
 * feeds 框架条目：Latest 聚合页里每个分类前的小标题。点 "查看更多" → 下钻该分类的完整 list。
 * [showMore] 目前恒为 true（唯一调用方 [InfoLatestFragment] 一直这么传），保留字段只是不丢旧接口
 * 表达的能力，不是当前有第二个调用方在用它。
 */
data class InfoCategoryHeaderFeedItem(
    val category: CategorizedInfo,
    val showMore: Boolean = true,
) : FeedItem {
    // header id 与 InfoItem.id 不重叠
    override val feedKey: Any get() = -(category.category_id.toLong() + 1L)
}

/**
 * 分类小标题 cell 的实际渲染逻辑。整行不响应点击,只有"查看更多"自己接事件。
 * 只画内容、不挂监听——按框架约定监听在 renderer 的 `create` 里挂一次（用 `cell.item` 取当下条目）。
 */
fun CellInfoCategoryHeaderBinding.bindInfoCategoryHeader(item: InfoCategoryHeaderFeedItem) {
    categoryTitle.text = item.category.category_title.orEmpty()
    categoryMore.isVisible = item.showMore
    categoryMore.isClickable = item.showMore
}

/** feeds 框架条目，被 [InfoLatestFragment] 和 [InfoCategoryListFragment] 共用。 */
data class InfoEntryFeedItem(val item: InfoItem) : FeedItem {
    override val feedKey: Any get() = item.id
}

/** 公告条目 cell 的实际渲染逻辑（点击监听同样归 renderer 的 `create`）。 */
fun CellInfoEntryBinding.bindInfoEntry(item: InfoItem) {
    infoTitle.text = item.title.orEmpty()
    infoDate.text = item.date?.take(10).orEmpty() // "2026-04-21T13:00:00+09:00" → "2026-04-21"
    infoRecentDot.isVisible = item.is_recent
}
