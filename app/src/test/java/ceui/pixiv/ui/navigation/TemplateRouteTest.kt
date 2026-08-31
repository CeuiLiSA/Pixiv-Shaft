package ceui.pixiv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TemplateRoute.key] 是持久化/跨进程契约（`feature_table.dataType`、深链、通知
 * PendingIntent 都带着它），不是普通常量。这里用一份 golden list 把全部 key 钉死：
 * 改了任何一个字、或者两个常量撞 key，都会在这里炸，而不是等老用户的精华列打不开。
 *
 * 新增路由：往 [GOLDEN] 里追加一行即可；删除路由要先确认没有持久化数据还引用它。
 */
class TemplateRouteTest {

    @Test
    fun `key 唯一且 fromKey 能原样找回`() {
        val seen = HashMap<String, TemplateRoute>()
        for (route in TemplateRoute.entries) {
            assertTrue("blank key on $route", route.key.isNotBlank())
            val prev = seen.put(route.key, route)
            assertNull("duplicate key '${route.key}' on $route and $prev", prev)
            assertSame(route, TemplateRoute.fromKey(route.key))
        }
    }

    @Test
    fun `未知或空 key 返回 null 而不是抛`() {
        assertNull(TemplateRoute.fromKey(null))
        assertNull(TemplateRoute.fromKey(""))
        assertNull(TemplateRoute.fromKey("不存在的页面"))
        // key 是精确匹配，不做 trim / 大小写归一
        assertNull(TemplateRoute.fromKey(" 设置"))
        assertNull(TemplateRoute.fromKey("primetagslist"))
    }

    @Test
    fun `golden list 与枚举一一对应`() {
        val actual = TemplateRoute.entries.associate { it.name to it.key }
        assertEquals(
            "enum 与 golden list 的常量集合不一致（新增/删除路由要同步更新 GOLDEN）",
            GOLDEN.keys,
            actual.keys,
        )
        for ((name, key) in GOLDEN) {
            assertEquals("route $name 的 key 被改了——它是持久化契约", key, actual[name])
        }
    }

    private companion object {
        val GOLDEN: Map<String, String> = mapOf(
            "LOGIN" to "登录注册",
            "RELATED_ILLUSTS" to "相关作品",
            "HISTORY" to "浏览记录",
            "WATCH_LATER" to "稍后再看",
            "WEB_LINK" to "网页链接",
            "NANA7MI_USAGE" to "借号用量",
            "SETTINGS" to "设置",
            "SETTINGS_CATEGORY" to "设置分类",
            "RECOMMENDED_USERS" to "推荐用户",
            "PIXIVISION" to "特辑",
            "REVERSE_IMAGE_SEARCH" to "以图搜图",
            "COMMENTS" to "相关评论",
            "ACCOUNT_SWITCH" to "账号管理",
            "BOOKED_TAG_FILTER" to "按标签筛选",
            "ABOUT" to "关于软件",
            "BULK_DOWNLOAD_QUEUE" to "批量下载队列",
            "BULK_SELECT" to "批量选择",
            "NOVEL_BULK_SELECT" to "小说批量选择",
            "WALKTHROUGH" to "画廊",
            "FOLLOWING" to "正在关注",
            "NICE_FRIENDS" to "好P友",
            "NICE_FRIEND_ILLUSTS" to "好P友作品",
            "SEARCH" to "搜索",
            "USER_INFO" to "详细信息",
            "NEW_WORKS" to "最新作品",
            "FANS" to "粉丝",
            "ILLUST_LIKERS" to "喜欢这个作品的用户",
            "NOVEL_LIKERS" to "喜欢这部小说的用户",
            "NOVEL_SERIES_DETAIL_LEGACY" to "小说系列详情",
            "USER_ILLUSTS" to "插画作品",
            "USER_ILLUSTS_BY_TAG" to "插画标签作品",
            "USER_MANGA_BY_TAG" to "漫画标签作品",
            "USER_NOVELS_BY_TAG" to "小说标签作品",
            "REQUEST_PLAN_DETAIL" to "约稿方案详情",
            "USER_MANGA" to "漫画作品",
            "ILLUST_BOOKMARKS" to "插画/漫画收藏",
            "DOWNLOAD_MANAGER" to "下载管理",
            "RECOMMENDED_MANGA" to "推荐漫画",
            "RECOMMENDED_NOVELS" to "推荐小说",
            "NOVEL_BOOKMARKS" to "小说收藏",
            "USER_NOVELS" to "小说作品",
            "NOVEL_DETAIL" to "小说详情",
            "NOVEL_READER" to "小说正文",
            "LOCAL_NOVEL_LIBRARY" to "本地小说库",
            "COMIC_READER" to "漫画阅读",
            "NOVEL_SERIES" to "小说系列",
            "WEB_HOME" to "Web首页",
            "WEB_PAGE" to "Web页面",
            "IMAGE_DETAIL" to "图片详情",
            "UPSCALE_COMPARE" to "画质增强对比",
            "AI_UPSCALE" to "AI画质提升",
            "REMBG_HIGHLIGHT" to "主体高亮",
            "REMBG_PREVIEW" to "抠图预览",
            "REMBG_MODEL_DOWNLOAD" to "模型下载",
            "TRANSLATION_MODEL_DOWNLOAD" to "翻译模型下载",
            "RIFE_MODEL_DOWNLOAD" to "RIFE补帧模型下载",
            "MANGA_OCR_MODEL_DOWNLOAD" to "漫画OCR模型下载",
            "COMIC_TEXT_DETECTOR_MODEL_DOWNLOAD" to "漫画文本框检测模型下载",
            "EDIT_ACCOUNT" to "绑定邮箱",
            "EMAIL_BACKUP" to "邮箱备份",
            "EDIT_PROFILE" to "编辑个人资料",
            "MUTED_TAGS" to "标签屏蔽记录",
            "FILE_NAME_FORMAT" to "修改命名方式",
            "DOWNLOAD_PATH_SETTINGS" to "下载路径与文件名",
            "ARIA2_SETTINGS" to "aria2远程下载",
            "AI_TRANSLATE_SETTINGS" to "自定义AI翻译",
            "NOVEL_HEADER_SETTINGS" to "小说信息头",
            "DONATE" to "捐赠",
            "FOLLOWING_NOVELS" to "关注者的小说",
            "USER_MANGA_SERIES" to "漫画系列作品",
            "MANGA_SERIES_DETAIL" to "漫画系列详情",
            "USER_NOVEL_SERIES" to "小说系列作品",
            "FEATURE_LIST" to "精华列",
            "WORKSPACE" to "我的作业环境",
            "PRIME_TAGS" to "PrimeTagsList",
            "PINNED_TAGS" to "PinnedTagsList",
            "PRIME_TAG_DETAIL" to "PrimeTagDetail",
            "MY_ILLUST_COLLECTION" to "我的插画收藏",
            "MY_NOVEL_COLLECTION" to "我的小说收藏",
            "WATCHLIST" to "追更列表",
            "MY_FOLLOWING" to "我的关注",
            "NOVEL_MARKERS" to "小说书签",
            "THEME_COLOR" to "主题颜色",
            "SAF_TEST" to "测试测试",
            "FLAG_REASON" to "举报插画",
            "FLAG_DESC" to "填写举报详细信息",
            "RELATED_USERS" to "相关用户",
            "MARKDOWN" to "Markdown",
            "VERSION_HISTORY" to "版本历史",
            "DISCOVERY" to "发现",
            "RECENT_RECOMMEND" to "当前最热",
            "SITE_RECOMMEND" to "站长推荐",
            "ARTIST_RANK" to "画师榜",
            "ARTIST_AVG_RANK" to "画师均分榜",
            "VIEW_RANK" to "浏览量榜",
            "PIXIV_COMIC" to "pixiv漫画",
            "FANBOX_HOME" to "FANBOX首页",
            "FANBOX_POST" to "FANBOX帖子",
            "BOOKMARK_RANK" to "收藏榜",
            "AI_RANK" to "AI榜",
            "SFW_RANK" to "全年龄榜",
            "YEAR_RANK" to "年代榜",
            "TAG_RANK" to "标签榜",
            "WALLPAPER_RANK" to "壁纸榜",
            "SERIES_RANK" to "系列榜",
            "MONTH_RANK" to "新作榜",
            "NOVEL_LENGTH_RANK" to "长篇小说榜",
            "TRENDING_ARTISTS" to "人气画师",
            "UGOIRA_RANK" to "动图榜",
            "EVENT_HISTORY" to "操作记录",
            "DEBUG_BULK_DOWNLOAD" to "批量下载Debug",
            "DEBUG_SAF_PERF" to "SAF写入压测",
            "DEBUG_NETWORK_TEST" to "网络测试",
            "DEBUG_POPULAR_TAG_EXPORT" to "标签热度导出",
            "SYNONYM_DICT" to "同义词词典",
            "CHAT" to "聊天室",
            "CHAT_GLOBAL_ROOM" to "聊天-全员公屏",
            "PLAZA" to "广场",
            "PLAZA_COMPOSE" to "发帖",
            "PLAZA_OPEN_ILLUST" to "Plaza打开作品",
            "PLAZA_POST_DETAIL" to "Plaza帖子详情",
            "NOTIFICATION_CENTER" to "通知中心",
            "NOTIFICATION_VIEW_MORE" to "通知展开",
            "INFO_CATEGORY" to "公告分类",
            "SNAPSHOT_MANAGER" to "离线快照",
            "SNAPSHOT_VIEW" to "快照查看",
            "SNAPSHOT_VIEW_CLASSIC" to "快照经典查看",
            "SNAPSHOT_COMMENTS" to "快照评论",
        )
    }
}
