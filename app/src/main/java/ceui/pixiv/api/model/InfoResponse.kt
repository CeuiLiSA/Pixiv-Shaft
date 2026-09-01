package ceui.pixiv.api.model

import java.io.Serializable

/**
 * /v1/info/latest —— 首屏聚合接口，按 category 分块返回，每块只给前几条。
 * 拿来铺 InfoLatestFragment 的首屏（"全部 + 各分类的近期 N 条"），没有 next_url。
 *
 * 适配 DataSource：把 CategorizedInfo 当 item，itemMapper 再展开成 header + entries。
 */
data class InfoLatestResponse(
    val categorized_infos: List<CategorizedInfo> = listOf(),
) : Serializable, KListShow<CategorizedInfo> {
    override val displayList: List<CategorizedInfo> get() = categorized_infos
    override val nextPageUrl: String? get() = null
}

/**
 * /v1/info/list?cid=N —— 单一分类的完整列表，支持 next_url 翻页。
 * 注意字段是单数 categorized_info（latest 那个是复数 categorized_infos）。
 */
data class InfoListResponse(
    val categorized_info: CategorizedInfo? = null,
    val next_url: String? = null,
) : Serializable, KListShow<InfoItem> {
    override val displayList: List<InfoItem> get() = categorized_info?.info_list ?: listOf()
    override val nextPageUrl: String? get() = next_url
}

data class CategorizedInfo(
    val category_id: Int = 0,
    val category_title: String? = null,
    val info_list: List<InfoItem> = listOf(),
) : Serializable

data class InfoItem(
    val id: Long = 0L,
    val title: String? = null,
    val date: String? = null,
    val url: String? = null,
    val is_recent: Boolean = false,
) : Serializable
