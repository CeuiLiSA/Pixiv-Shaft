package ceui.pixiv.ui.navigation

/**
 * [ceui.lisa.activities.TemplateActivity] 能装的全部页面，一条路由一个常量。
 *
 * [key] 是 Intent extra `EXTRA_FRAGMENT` 里真正传的字符串。它**不是展示文案**，而是
 * 一份线上契约：
 *  - `feature_table.dataType`（精华列）把它落进了 Room，老库里的行靠它重建页面；
 *  - 外部 deep link / 通知 PendingIntent / 桌面快捷方式可能带着旧值进来。
 * 所以枚举常量可以随便改名，[key] 一个字都不能动；新增页面只加常量、不复用旧 key。
 * `TemplateRouteTest` 用一份 golden list 锁住全部 key，改错会在单测里炸。
 *
 * 建 Fragment 的逻辑在 [TemplateRouteFactory]——穷举 `when`，漏了哪个常量编译直接报错，
 * 比 `Map<Route, Factory>` 多一层编译期保证。
 *
 * 调用方写法：`intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SETTINGS.key)`。
 * Java 侧同样是 `.key`（`@JvmField`）。
 */
enum class TemplateRoute(@JvmField val key: String) {
    LOGIN("登录注册"),
    RELATED_ILLUSTS("相关作品"),
    HISTORY("浏览记录"),
    WATCH_LATER("稍后再看"),
    WEB_LINK("网页链接"),
    NANA7MI_USAGE("借号用量"),
    SETTINGS("设置"),
    SETTINGS_CATEGORY("设置分类"),
    RECOMMENDED_USERS("推荐用户"),
    PIXIVISION("特辑"),
    REVERSE_IMAGE_SEARCH("以图搜图"),
    COMMENTS("相关评论"),
    ACCOUNT_SWITCH("账号管理"),
    BOOKED_TAG_FILTER("按标签筛选"),
    ABOUT("关于软件"),
    BULK_DOWNLOAD_QUEUE("批量下载队列"),
    BULK_SELECT("批量选择"),
    NOVEL_BULK_SELECT("小说批量选择"),
    WALKTHROUGH("画廊"),
    FOLLOWING("正在关注"),
    NICE_FRIENDS("好P友"),
    NICE_FRIEND_ILLUSTS("好P友作品"),
    SEARCH("搜索"),
    USER_INFO("详细信息"),
    NEW_WORKS("最新作品"),
    FANS("粉丝"),
    ILLUST_LIKERS("喜欢这个作品的用户"),
    NOVEL_LIKERS("喜欢这部小说的用户"),
    /** 旧路由，桥到 [NOVEL_SERIES]；保留只为兼容外部深链。内部入口一律用 [NOVEL_SERIES]。 */
    NOVEL_SERIES_DETAIL_LEGACY("小说系列详情"),
    USER_ILLUSTS("插画作品"),
    USER_ILLUSTS_BY_TAG("插画标签作品"),
    USER_MANGA_BY_TAG("漫画标签作品"),
    USER_NOVELS_BY_TAG("小说标签作品"),
    REQUEST_PLAN_DETAIL("约稿方案详情"),
    USER_MANGA("漫画作品"),
    ILLUST_BOOKMARKS("插画/漫画收藏"),
    DOWNLOAD_MANAGER("下载管理"),
    RECOMMENDED_MANGA("推荐漫画"),
    RECOMMENDED_NOVELS("推荐小说"),
    NOVEL_BOOKMARKS("小说收藏"),
    USER_NOVELS("小说作品"),
    NOVEL_DETAIL("小说详情"),
    NOVEL_READER("小说正文"),
    LOCAL_NOVEL_LIBRARY("本地小说库"),
    COMIC_READER("漫画阅读"),
    NOVEL_SERIES("小说系列"),
    WEB_HOME("Web首页"),
    WEB_PAGE("Web页面"),
    IMAGE_DETAIL("图片详情"),
    UPSCALE_COMPARE("画质增强对比"),
    AI_UPSCALE("AI画质提升"),
    REMBG_HIGHLIGHT("主体高亮"),
    REMBG_PREVIEW("抠图预览"),
    REMBG_MODEL_DOWNLOAD("模型下载"),
    TRANSLATION_MODEL_DOWNLOAD("翻译模型下载"),
    RIFE_MODEL_DOWNLOAD("RIFE补帧模型下载"),
    MANGA_OCR_MODEL_DOWNLOAD("漫画OCR模型下载"),
    COMIC_TEXT_DETECTOR_MODEL_DOWNLOAD("漫画文本框检测模型下载"),
    EDIT_ACCOUNT("绑定邮箱"),
    EMAIL_BACKUP("邮箱备份"),
    EDIT_PROFILE("编辑个人资料"),
    MUTED_TAGS("标签屏蔽记录"),
    FILE_NAME_FORMAT("修改命名方式"),
    DOWNLOAD_PATH_SETTINGS("下载路径与文件名"),
    ARIA2_SETTINGS("aria2远程下载"),
    AI_TRANSLATE_SETTINGS("自定义AI翻译"),
    NOVEL_HEADER_SETTINGS("小说信息头"),
    DONATE("捐赠"),
    FOLLOWING_NOVELS("关注者的小说"),
    USER_MANGA_SERIES("漫画系列作品"),
    MANGA_SERIES_DETAIL("漫画系列详情"),
    USER_NOVEL_SERIES("小说系列作品"),
    FEATURE_LIST("精华列"),
    WORKSPACE("我的作业环境"),
    PRIME_TAGS("PrimeTagsList"),
    PINNED_TAGS("PinnedTagsList"),
    PRIME_TAG_DETAIL("PrimeTagDetail"),
    MY_ILLUST_COLLECTION("我的插画收藏"),
    MY_NOVEL_COLLECTION("我的小说收藏"),
    /** 收藏库：本地收藏镜像的浏览/筛选页（倒序、按标签/作者/年份筛、全文搜、随机漫游）。 */
    BOOKMARK_LIBRARY("收藏库"),
    WATCHLIST("追更列表"),
    MY_FOLLOWING("我的关注"),
    NOVEL_MARKERS("小说书签"),
    THEME_COLOR("主题颜色"),
    SAF_TEST("测试测试"),
    FLAG_REASON("举报插画"),
    FLAG_DESC("填写举报详细信息"),
    RELATED_USERS("相关用户"),
    MARKDOWN("Markdown"),
    VERSION_HISTORY("版本历史"),
    DISCOVERY("发现"),
    RECENT_RECOMMEND("当前最热"),
    SITE_RECOMMEND("站长推荐"),
    ARTIST_RANK("画师榜"),
    ARTIST_AVG_RANK("画师均分榜"),
    VIEW_RANK("浏览量榜"),
    PIXIV_COMIC("pixiv漫画"),
    FANBOX_HOME("FANBOX首页"),
    FANBOX_POST("FANBOX帖子"),
    BOOKMARK_RANK("收藏榜"),
    AI_RANK("AI榜"),
    SFW_RANK("全年龄榜"),
    YEAR_RANK("年代榜"),
    TAG_RANK("标签榜"),
    WALLPAPER_RANK("壁纸榜"),
    SERIES_RANK("系列榜"),
    MONTH_RANK("新作榜"),
    NOVEL_LENGTH_RANK("长篇小说榜"),
    TRENDING_ARTISTS("人气画师"),
    UGOIRA_RANK("动图榜"),
    EVENT_HISTORY("操作记录"),
    DEBUG_BULK_DOWNLOAD("批量下载Debug"),
    DEBUG_SAF_PERF("SAF写入压测"),
    DEBUG_NETWORK_TEST("网络测试"),
    DEBUG_POPULAR_TAG_EXPORT("标签热度导出"),
    SYNONYM_DICT("同义词词典"),
    CHAT("聊天室"),
    CHAT_GLOBAL_ROOM("聊天-全员公屏"),
    PLAZA("广场"),
    PLAZA_COMPOSE("发帖"),
    PLAZA_OPEN_ILLUST("Plaza打开作品"),
    PLAZA_POST_DETAIL("Plaza帖子详情"),
    NOTIFICATION_CENTER("通知中心"),
    NOTIFICATION_VIEW_MORE("通知展开"),
    INFO_CATEGORY("公告分类"),
    SNAPSHOT_MANAGER("离线快照"),
    SNAPSHOT_VIEW("快照查看"),
    SNAPSHOT_VIEW_CLASSIC("快照经典查看"),
    SNAPSHOT_COMMENTS("快照评论"),
    ;

    companion object {
        private val byKey: Map<String, TemplateRoute> = entries.associateBy { it.key }.also {
            // 两个常量共用一个 key 会让 associateBy 静默吞掉一个；这是编程错误，类加载时就炸。
            check(it.size == entries.size) { "duplicate TemplateRoute key" }
        }

        /** 未知 / null key 返回 null，由 TemplateActivity 决定怎么兜底（debug 崩、release 收掉）。 */
        @JvmStatic
        fun fromKey(key: String?): TemplateRoute? = key?.let(byKey::get)
    }
}
