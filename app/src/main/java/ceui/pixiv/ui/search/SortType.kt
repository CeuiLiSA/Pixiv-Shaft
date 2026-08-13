package ceui.pixiv.ui.search

object SortType {
    const val POPULAR_PREVIEW = "popular_preview"
    const val DATE_DESC = "date_desc"
    const val DATE_ASC = "date_asc"
    const val POPULAR_DESC = "popular_desc"
    // 仅 illust/manga + 会员；非会员选了也会被 [shouldUsePopularPreview] 兜底走 popular-preview
    const val POPULAR_MALE_DESC = "popular_male_desc"
    const val POPULAR_FEMALE_DESC = "popular_female_desc"
    const val TRENDING_BUILTIN = "trending_builtin"

    /**
     * 小说侧的实际 sort。
     *
     * [TRENDING_BUILTIN]（「机内自带热度排序」）是 App 本地概念：插画走
     * [ceui.pixiv.ui.prime.PrimeIllustLoader] 读 assets 内置榜，在
     * [ceui.lisa.repo.SearchIllustRepo.initApi] 开头就被截走，从不发给 pixiv。小说没有这份内置
     * 数据，而 `/v1/search/novel` 也不认这个 sort 值——原样发出去必然 400 Invalid value（非会员
     * 还会先白借一个号去撞）。它的语义就是「按热度排」，所以小说一律归一到官方 [POPULAR_DESC]：
     * 非会员由借号跑，会员用自己的号跑，两条路都能真排出热度。
     */
    fun forNovel(sort: String?): String? =
        if (sort == TRENDING_BUILTIN) POPULAR_DESC else sort
}
