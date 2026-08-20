package ceui.pixiv.ui.search

object SortType {
    const val POPULAR_PREVIEW = "popular_preview"
    const val DATE_DESC = "date_desc"
    const val DATE_ASC = "date_asc"
    const val POPULAR_DESC = "popular_desc"
    // 仅 illust/manga；非会员选了走借号（SearchIllustRepo.wantsPremiumOnlySort 与 popular_desc
    // 同一条路由），借不到号再兜底 popular-preview
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

    /**
     * novel 侧的 sort 归一：男/女性向人气是 illust/manga 专属档，novel 的 app-api 端点不识别。
     * 但「搜索结果排序方式」是全局设置（设置页与 V3 筛选器共享同一字段），插画侧存了
     * 男/女性向后，novel 的默认 sort / 共享 SearchModel.sortType 都可能带上它——发出去
     * 是 400，非会员那条路还会先白借一个号。所以 novel 路径拿 sort 时一律过这层，
     * 归一到语义最近的总热度。
     */
    @JvmStatic
    fun novelSafe(sort: String?): String? = when (sort) {
        POPULAR_MALE_DESC, POPULAR_FEMALE_DESC -> POPULAR_DESC
        else -> sort
    }
}
