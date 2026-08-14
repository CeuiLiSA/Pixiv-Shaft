package ceui.pixiv.ui.search

object SortType {
    const val POPULAR_PREVIEW = "popular_preview"
    const val DATE_DESC = "date_desc"
    const val DATE_ASC = "date_asc"
    const val POPULAR_DESC = "popular_desc"
    // 仅 illust/manga + 会员；非会员选了也会被 [shouldUsePopularPreview] 兜底走 popular-preview
    const val POPULAR_MALE_DESC = "popular_male_desc"
    const val POPULAR_FEMALE_DESC = "popular_female_desc"

    /**
     * 「机内自带热度排序」—— **已下线，不再可选**。
     *
     * 它读的是打包在 APK 里 `assets/pixiv_prime/` 下的内置榜 txt（183MB 原始、安装包约 19MB）。
     * 那份数据已经搬到 pixshaft-api，只服务 Prime 标签页（见 [ceui.pixiv.ui.prime.PrimeTagDetailFragment]），
     * 搜索这边没有本地榜可读了，档位随之撤掉。
     *
     * 常量留着是因为**老配置里存过这个值**（设置页的「搜索结果默认排序」曾经能选它），
     * 升级上来的用户 `Shaft.sSettings.searchDefaultSortType` 里就是它。原样发给 pixiv 必然
     * 400 Invalid value，非会员那条路还会先白借一个号去撞——所以一律经 [sanitize] 归一。
     */
    const val TRENDING_BUILTIN = "trending_builtin"

    /**
     * 归一化从设置 / 老状态里带上来的 sort 值：把已下线的 [TRENDING_BUILTIN] 换成官方
     * [POPULAR_DESC]，其余原样透传。
     *
     * 语义不变——两者都是「按热度排」。非会员选 popular_desc 会被各 repo 自动路由到
     * popular-preview / 借号，热度照样排得出来。
     */
    fun sanitize(sort: String?): String? = if (sort == TRENDING_BUILTIN) POPULAR_DESC else sort
}
