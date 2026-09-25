package ceui.pixiv.ui.library

import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 书架内容类型 → 本地库页面上随之变化的全部差异。
 *
 * 插画收藏、小说收藏、关注三种本地库共用同一份页面接线（[BookmarkLibraryUi]）、同一个筛选面板
 * （[BookmarkFilterSheet]）和同一个数据源（[BookmarkLibraryFeedSource]），逻辑上没有任何分叉；
 * 「这是哪一种库」只决定文案、可选的排序和面板露出哪几节，全部在这里集中声明。
 * 加一种可镜像的列表 = 加一个 [MirrorContentType] + 在 [of] 里补一份档案，页面代码不用动。
 */
internal class LibraryProfile private constructor(
    val contentType: MirrorContentType,
    @StringRes val publicShelf: Int,
    @StringRes val privateShelf: Int,
    /** 「原始列表」退路落在哪个路由上（带 Params.FLAG，那边据此不再重定向回本地库）。 */
    val classicRoute: TemplateRoute,
    @StringRes val openClassic: Int,
    @StringRes val searchHint: Int,
    @StringRes val empty: Int,
    @StringRes val emptyFiltered: Int,
    /** 「正在后台补齐 · 已 N 件」，%1$s = 已镜像条数。 */
    @StringRes val syncing: Int,
    @StringRes val syncQueued: Int,
    /** 「共 N 件」，%1$s = 书架总数。 */
    @StringRes val totalCount: Int,
    /** 面板底部主操作「查看 N 件」，%1$s = 当前命中数。 */
    @StringRes val showResults: Int,
    val sorts: List<BookmarkSort>,
    /** 作品维度的筛选（分级 / AI / 作品状态 / 人气 / 系列 / 作者）。关注书架里一行是一个人，没有这些。 */
    val hasWorkFilters: Boolean,
    /** 按 `createDateMs` 分年的那一节叫什么：作品是「发布年份」，关注是「最近投稿年份」。 */
    @StringRes val yearSection: Int,
    @StringRes val tagHint: Int,
) {

    val isIllust: Boolean get() = contentType == MirrorContentType.ILLUST

    val isNovel: Boolean get() = contentType == MirrorContentType.NOVEL

    /** 同一个排序键在不同书架上的说法：关注书架的「收藏时间」是关注时间，「发布时间」是最近投稿。 */
    @StringRes
    fun sortLabel(sort: BookmarkSort): Int {
        if (contentType == MirrorContentType.USER) {
            when (sort) {
                BookmarkSort.BOOKMARK_NEWEST -> return R.string.following_sort_followed_newest
                BookmarkSort.BOOKMARK_OLDEST -> return R.string.following_sort_followed_oldest
                BookmarkSort.CREATED_NEWEST -> return R.string.following_sort_active_newest
                BookmarkSort.CREATED_OLDEST -> return R.string.following_sort_active_oldest
                BookmarkSort.TITLE_ASC -> return R.string.following_sort_name_asc
                else -> Unit
            }
        }
        return when (sort) {
            BookmarkSort.BOOKMARK_NEWEST -> R.string.bookmark_sort_bookmark_newest
            BookmarkSort.BOOKMARK_OLDEST -> R.string.bookmark_sort_bookmark_oldest
            BookmarkSort.CREATED_NEWEST -> R.string.bookmark_sort_created_newest
            BookmarkSort.CREATED_OLDEST -> R.string.bookmark_sort_created_oldest
            BookmarkSort.POPULAR_DESC -> R.string.bookmark_sort_popular_desc
            BookmarkSort.POPULAR_ASC -> R.string.bookmark_sort_popular_asc
            BookmarkSort.VIEWS_DESC -> R.string.bookmark_sort_views_desc
            BookmarkSort.PAGES_DESC -> R.string.bookmark_sort_pages_desc
            BookmarkSort.RATIO_TALLEST -> R.string.bookmark_sort_ratio_tallest
            BookmarkSort.RATIO_WIDEST -> R.string.bookmark_sort_ratio_widest
            BookmarkSort.LENGTH_DESC -> R.string.bookmark_sort_length_desc
            BookmarkSort.LENGTH_ASC -> R.string.bookmark_sort_length_asc
            BookmarkSort.TITLE_ASC -> R.string.bookmark_sort_title_asc
            BookmarkSort.RANDOM -> R.string.bookmark_sort_random
        }
    }

    companion object {

        private val WORK_SORTS_HEAD = listOf(
            BookmarkSort.BOOKMARK_NEWEST,
            BookmarkSort.BOOKMARK_OLDEST,
            BookmarkSort.CREATED_NEWEST,
            BookmarkSort.CREATED_OLDEST,
            BookmarkSort.POPULAR_DESC,
            BookmarkSort.POPULAR_ASC,
            BookmarkSort.VIEWS_DESC,
        )

        private val ILLUST = LibraryProfile(
            contentType = MirrorContentType.ILLUST,
            publicShelf = R.string.public_like_illust,
            privateShelf = R.string.private_like_illust,
            classicRoute = TemplateRoute.MY_ILLUST_COLLECTION,
            openClassic = R.string.bookmark_library_open_classic,
            searchHint = R.string.bookmark_library_search_hint,
            empty = R.string.bookmark_library_empty,
            emptyFiltered = R.string.bookmark_library_empty_filtered,
            syncing = R.string.bookmark_library_syncing,
            syncQueued = R.string.bookmark_library_sync_queued,
            totalCount = R.string.bookmark_library_total_count,
            showResults = R.string.bookmark_library_filter_apply,
            sorts = WORK_SORTS_HEAD + listOf(
                BookmarkSort.PAGES_DESC,
                BookmarkSort.RATIO_TALLEST,
                BookmarkSort.RATIO_WIDEST,
                BookmarkSort.TITLE_ASC,
                BookmarkSort.RANDOM,
            ),
            hasWorkFilters = true,
            yearSection = R.string.bookmark_filter_section_year,
            tagHint = R.string.bookmark_filter_tag_hint,
        )

        private val NOVEL = LibraryProfile(
            contentType = MirrorContentType.NOVEL,
            publicShelf = R.string.public_like_novel,
            privateShelf = R.string.private_like_novel,
            classicRoute = TemplateRoute.MY_NOVEL_COLLECTION,
            openClassic = R.string.bookmark_library_open_classic,
            searchHint = R.string.bookmark_library_search_hint,
            empty = R.string.bookmark_library_empty,
            emptyFiltered = R.string.bookmark_library_empty_filtered,
            syncing = R.string.bookmark_library_syncing,
            syncQueued = R.string.bookmark_library_sync_queued,
            totalCount = R.string.bookmark_library_total_count,
            showResults = R.string.bookmark_library_filter_apply,
            sorts = WORK_SORTS_HEAD + listOf(
                BookmarkSort.LENGTH_DESC,
                BookmarkSort.LENGTH_ASC,
                BookmarkSort.TITLE_ASC,
                BookmarkSort.RANDOM,
            ),
            hasWorkFilters = true,
            yearSection = R.string.bookmark_filter_section_year,
            tagHint = R.string.bookmark_filter_tag_hint,
        )

        private val USER = LibraryProfile(
            contentType = MirrorContentType.USER,
            publicShelf = R.string.public_like_user,
            privateShelf = R.string.private_like_user,
            classicRoute = TemplateRoute.MY_FOLLOWING,
            openClassic = R.string.following_library_open_classic,
            searchHint = R.string.following_library_search_hint,
            empty = R.string.following_library_empty,
            emptyFiltered = R.string.following_library_empty_filtered,
            syncing = R.string.following_library_syncing,
            syncQueued = R.string.following_library_sync_queued,
            totalCount = R.string.following_library_total_count,
            showResults = R.string.following_library_filter_apply,
            // 人气 / 浏览量 / 页数 / 字数 / 宽高比都是作品的属性，对人没有意义
            sorts = listOf(
                BookmarkSort.BOOKMARK_NEWEST,
                BookmarkSort.BOOKMARK_OLDEST,
                BookmarkSort.CREATED_NEWEST,
                BookmarkSort.CREATED_OLDEST,
                BookmarkSort.TITLE_ASC,
                BookmarkSort.RANDOM,
            ),
            hasWorkFilters = false,
            yearSection = R.string.following_filter_section_year,
            tagHint = R.string.following_filter_tag_hint,
        )

        fun of(contentType: MirrorContentType): LibraryProfile = when (contentType) {
            MirrorContentType.ILLUST -> ILLUST
            MirrorContentType.NOVEL -> NOVEL
            MirrorContentType.USER -> USER
        }
    }
}
