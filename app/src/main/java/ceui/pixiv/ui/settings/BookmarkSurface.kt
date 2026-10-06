package ceui.pixiv.ui.settings

import androidx.annotation.StringRes
import ceui.lisa.R

/**
 * 「作品卡片上显示收藏按钮」统一入口里可独立开关的一个卡面。
 *
 * 一个枚举值 = 弹窗里一行开关 = 用户认得出来的一个页面。`key` 是落进
 * [ceui.lisa.utils.Settings] 的 hiddenBookmarkSurfaces 集合里的稳定键 ——
 * **改名等于丢掉用户的选择**（老配置里那个键会认不出来，只被忽略）。
 *
 * 分组只服务于弹窗排版：常用排前，其余收进「更多」。
 */
enum class BookmarkSurface(
    /** 落进设置的稳定键。改名 = 丢掉用户的选择。 */
    val key: String,
    @StringRes val labelRes: Int,
    val group: Group,
) {

    // ── 常用 ────────────────────────────────────────────────────────────
    HOME_ILLUST("home_illust", R.string.bookmark_surface_home_illust, Group.COMMON),
    HOME_MANGA("home_manga", R.string.bookmark_surface_home_manga, Group.COMMON),
    FOLLOWING("following", R.string.bookmark_surface_following, Group.COMMON),
    DISCOVERY("discovery", R.string.bookmark_surface_discovery, Group.COMMON),
    RANK("rank", R.string.bookmark_surface_rank, Group.COMMON),
    SEARCH("search", R.string.bookmark_surface_search, Group.COMMON),
    USER("user", R.string.bookmark_surface_user, Group.COMMON),
    RELATED("related", R.string.bookmark_surface_related, Group.COMMON),
    DETAIL_RELATED("detail_related", R.string.bookmark_surface_detail_related, Group.COMMON),
    MY_COLLECTION("my_collection", R.string.bookmark_surface_my_collection, Group.COMMON),
    BOOKMARK_LIBRARY("bookmark_library", R.string.bookmark_surface_bookmark_library, Group.COMMON),
    WATCH_LATER("watch_later", R.string.bookmark_surface_watch_later, Group.COMMON),
    WIDGET("widget", R.string.bookmark_surface_widget, Group.COMMON),

    // ── 更多 ────────────────────────────────────────────────────────────
    WEB_DISCOVERY("web_discovery", R.string.bookmark_surface_web_discovery, Group.MORE),
    LATEST("latest", R.string.bookmark_surface_latest, Group.MORE),
    HOT_WORKS("hot_works", R.string.bookmark_surface_hot_works, Group.MORE),
    BOOKMARK_RANK("bookmark_rank", R.string.bookmark_surface_bookmark_rank, Group.MORE),
    VIEW_RANK("view_rank", R.string.bookmark_surface_view_rank, Group.MORE),
    DAILY_RECOMMEND("daily_recommend", R.string.bookmark_surface_daily_recommend, Group.MORE),
    WALLPAPER("wallpaper", R.string.bookmark_surface_wallpaper, Group.MORE),
    USER_BY_TAG("user_by_tag", R.string.bookmark_surface_user_by_tag, Group.MORE),
    PRIME_TAG("prime_tag", R.string.bookmark_surface_prime_tag, Group.MORE),
    CORPUS_TAG("corpus_tag", R.string.bookmark_surface_corpus_tag, Group.MORE),
    NICE_FRIEND("nice_friend", R.string.bookmark_surface_nice_friend, Group.MORE),
    WALKTHROUGH("walkthrough", R.string.bookmark_surface_walkthrough, Group.MORE),
    ;

    enum class Group { COMMON, MORE }

    companion object {
        /** 键 → 枚举。认不出的键返回 null：降级安装 / 手改配置只忽略，不连坐别的卡面。 */
        @JvmStatic
        fun ofKey(key: String?): BookmarkSurface? = entries.firstOrNull { it.key == key }

        /** 弹窗「常用」组的行，按枚举声明序。 */
        @JvmStatic
        fun commonGroup(): List<BookmarkSurface> = entries.filter { it.group == Group.COMMON }

        /** 弹窗「更多」组的行，按枚举声明序。 */
        @JvmStatic
        fun moreGroup(): List<BookmarkSurface> = entries.filter { it.group == Group.MORE }
    }
}