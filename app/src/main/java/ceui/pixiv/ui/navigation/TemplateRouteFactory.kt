package ceui.pixiv.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import ceui.lisa.activities.TemplateActivity
import ceui.loxia.appServices
import ceui.lisa.fragments.FragmentAboutApp
import ceui.lisa.fragments.FragmentCollection
import ceui.lisa.fragments.FragmentDonate
import ceui.lisa.fragments.FragmentEditAccount
import ceui.lisa.fragments.FragmentEditFile
import ceui.lisa.fragments.FragmentFileName
import ceui.lisa.fragments.FragmentHistoryTabs
import ceui.lisa.fragments.FragmentIllust
import ceui.lisa.fragments.FragmentImageDetail
import ceui.lisa.fragments.FragmentLogin
import ceui.lisa.fragments.FragmentMarkdown
import ceui.lisa.fragments.FragmentNew
import ceui.lisa.fragments.FragmentNewNovel
import ceui.lisa.fragments.FragmentPv
import ceui.lisa.fragments.FragmentSAF
import ceui.lisa.fragments.FragmentSearch
import ceui.lisa.fragments.FragmentSettingsHub
import ceui.lisa.fragments.FragmentUserInfo
import ceui.lisa.fragments.FragmentViewPager
import ceui.lisa.fragments.FragmentWebView
import ceui.lisa.fragments.FragmentWorkSpace
import ceui.lisa.fragments.SettingsCatalog
import ceui.lisa.fragments.StreetMainFragment
import ceui.lisa.update.FragmentVersionHistory
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.User
import ceui.loxia.flag.FlagDescFragment
import ceui.loxia.flag.FlagReasonFragment
import ceui.pixiv.chat.ui.ChatRoomListFragment
import ceui.pixiv.chat.ui.DemoChatListFragment
import ceui.pixiv.plaza.ui.PlazaComposeFragment
import ceui.pixiv.plaza.ui.PlazaFragment
import ceui.pixiv.plaza.ui.PlazaPostDetailFragment
import ceui.pixiv.snapshot.SnapshotManagerFragment
import ceui.pixiv.ui.account.AccountSwitchV3Fragment
import ceui.pixiv.ui.account.EmailBackupV3Fragment
import ceui.pixiv.ui.bulk.BulkSelectHandoff
import ceui.pixiv.ui.bulk.BulkSelectV3Fragment
import ceui.pixiv.ui.bulk.NovelBulkSelectV3Fragment
import ceui.pixiv.ui.collection.BookedTagFeedFragment
import ceui.pixiv.ui.collection.LikeIllustFeedFragment
import ceui.pixiv.ui.collection.LikeNovelFeedFragment
import ceui.pixiv.ui.comic.ComicTopFeedFragment
import ceui.pixiv.ui.comic.reader.ComicReaderV3Fragment
import ceui.pixiv.ui.comments.CommentsFragment
import ceui.pixiv.ui.debug.BulkDownloadDebugFragment
import ceui.pixiv.ui.debug.NetworkTestFragment
import ceui.pixiv.ui.debug.PopularTagExportFragment
import ceui.pixiv.ui.debug.SafPerfTestFragment
import ceui.pixiv.ui.detail.ArtworkV3Fragment
import ceui.pixiv.ui.detail.IllustSeriesFragment
import ceui.pixiv.ui.detail.RelatedIllustFeedFragment
import ceui.pixiv.ui.discovery.DiscoveryFeedFragment
import ceui.pixiv.ui.download.DownloadManagerV3Fragment
import ceui.pixiv.ui.dynamic.FollowingNovelFeedFragment
import ceui.pixiv.ui.fanbox.FanboxHomeFragment
import ceui.pixiv.ui.fanbox.FanboxPostDetailFragment
import ceui.pixiv.ui.feature.FeatureFeedFragment
import ceui.pixiv.ui.home.NiceFriendIllustFeedFragment
import ceui.pixiv.ui.home.RecmdMangaFeedFragment
import ceui.pixiv.ui.home.WalkthroughFeedFragment
import ceui.pixiv.ui.interpolate.RifeDownloadFragment
import ceui.pixiv.ui.notification.InfoCategoryListFragment
import ceui.pixiv.ui.notification.NotificationPagerFragment
import ceui.pixiv.ui.notification.NotificationViewMoreFragment
import ceui.pixiv.ui.novel.NovelMarkersFeedFragment
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.novel.NovelTextFragment
import ceui.pixiv.ui.novel.local.LocalLibraryFragment
import ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment
import ceui.pixiv.ui.pinned.PinnedTabsFragment
import ceui.pixiv.ui.prime.PrimeTagDetailFragment
import ceui.pixiv.ui.prime.PrimeTagsFragment
import ceui.pixiv.ui.recommend.AI_ONLY
import ceui.pixiv.ui.recommend.ArtistRankFeedFragment
import ceui.pixiv.ui.recommend.BookmarkRankFragment
import ceui.pixiv.ui.recommend.FragmentEventHistory
import ceui.pixiv.ui.recommend.FragmentRecentRecommend
import ceui.pixiv.ui.recommend.FragmentSiteRecommend
import ceui.pixiv.ui.recommend.MonthRankFragment
import ceui.pixiv.ui.recommend.NovelLengthRankFragment
import ceui.pixiv.ui.recommend.RESTRICT_SFW
import ceui.pixiv.ui.recommend.SeriesRankFragment
import ceui.pixiv.ui.recommend.TagRankFragment
import ceui.pixiv.ui.recommend.TrendingArtistsFragment
import ceui.pixiv.ui.recommend.UgoiraRankFragment
import ceui.pixiv.ui.recommend.ViewRankFragment
import ceui.pixiv.ui.recommend.WallpaperRankFragment
import ceui.pixiv.ui.recommend.YearRankFragment
import ceui.pixiv.ui.settings.AiTranslateSettingsFragment
import ceui.pixiv.ui.settings.Aria2SettingsFragment
import ceui.pixiv.ui.settings.DownloadPathSettingsFragment
import ceui.pixiv.ui.settings.NovelHeaderSettingsFragment
import ceui.pixiv.ui.settings.ThemeColorFeedFragment
import ceui.pixiv.ui.synonym.SynonymDictFragment
import ceui.pixiv.ui.translate.ComicTextDetectorDownloadFragment
import ceui.pixiv.ui.translate.MangaOcrDownloadFragment
import ceui.pixiv.ui.translate.TranslationModelDownloadFragment
import ceui.pixiv.ui.upscale.FragmentAiUpscale
import ceui.pixiv.ui.upscale.RembgHighlightFragment
import ceui.pixiv.ui.upscale.RembgModelDownloadFragment
import ceui.pixiv.ui.upscale.RembgPreviewFragment
import ceui.pixiv.ui.upscale.UpscaleCompareFragment
import ceui.pixiv.ui.usage.Nana7miUsageFragment
import ceui.pixiv.ui.user.FollowUserFeedFragment
import ceui.pixiv.ui.user.LikeUsersFeedFragment
import ceui.pixiv.ui.user.NiceFriendFeedFragment
import ceui.pixiv.ui.user.RecmdUserFeedFragment
import ceui.pixiv.ui.user.RelatedUserFeedFragment
import ceui.pixiv.ui.user.RequestPlan
import ceui.pixiv.ui.user.RequestPlanDetailFragment
import ceui.pixiv.ui.user.UserFansFeedFragment
import ceui.pixiv.ui.user.UserIllustByTagFeedFragment
import ceui.pixiv.ui.user.UserIllustFeedFragment
import ceui.pixiv.ui.user.UserMangaFeedFragment
import ceui.pixiv.ui.user.UserMangaSeriesFeedFragment
import ceui.pixiv.ui.user.UserNovelByTagFeedFragment
import ceui.pixiv.ui.user.UserNovelFeedFragment
import ceui.pixiv.ui.user.UserNovelSeriesFeedFragment
import ceui.pixiv.ui.user.UserTagSearchSheet
import ceui.pixiv.ui.watchlater.WatchLaterTabsFragment
import ceui.pixiv.ui.web.WebFragment

/**
 * [TemplateRoute] → Fragment。原先是 TemplateActivity 里一个 128 case 的中文字符串 switch，
 * 现在 `when` 对枚举穷举：新增路由忘了在这里接就编译不过。
 *
 * 每个分支只做「从 Intent 取参 + newInstance」，不放业务逻辑；extra 的 key 沿用各页自己
 * 声明的常量，这里不另起名字。
 */
object TemplateRouteFactory {

    @JvmStatic
    fun create(route: TemplateRoute, intent: Intent): Fragment = when (route) {
        TemplateRoute.LOGIN -> FragmentLogin()
        TemplateRoute.RELATED_ILLUSTS -> RelatedIllustFeedFragment.newInstance(
            intent.getIntExtra(Params.ILLUST_ID, 0),
            intent.getStringExtra(Params.ILLUST_TITLE),
        )
        TemplateRoute.HISTORY -> FragmentHistoryTabs()
        TemplateRoute.WATCH_LATER -> WatchLaterTabsFragment()
        TemplateRoute.WEB_LINK -> FragmentWebView.newInstance(
            intent.getStringExtra(Params.TITLE),
            intent.getStringExtra(Params.URL),
            intent.getBooleanExtra(Params.PREFER_PRESERVE, false),
        )
        TemplateRoute.NANA7MI_USAGE -> Nana7miUsageFragment()
        TemplateRoute.SETTINGS -> FragmentSettingsHub()
        TemplateRoute.SETTINGS_CATEGORY -> SettingsCatalog.fragmentFor(
            intent.getStringExtra(SettingsCatalog.EXTRA_CATEGORY),
        )
        // 与 FragmentRight#seeMore 配对：货架把自己那批快照存进 RecmdUserHandoff，
        // 只把 key 传过来。取用/清理都归 RecmdUserFeedFragment 自己（数据落进 VM，
        // 旋转不重拉；key 为 null 或 map 已失效时它自己退化成网络首屏）。
        TemplateRoute.RECOMMENDED_USERS -> RecmdUserFeedFragment.newInstance(
            intent.getStringExtra(Params.USER_MODEL),
        )
        TemplateRoute.PIXIVISION -> FragmentPv()
        // 搜索本身由 WebView 发（引擎都在 Cloudflare 质询后面，见 ReverseImage），
        // 这里只是把引擎上传页和待搜图片转交过去。
        TemplateRoute.REVERSE_IMAGE_SEARCH -> FragmentWebView.newInstance(
            intent.getStringExtra(Params.TITLE),
            intent.getStringExtra(Params.URL),
            IntentCompat.getParcelableExtra(intent, Params.REVERSE_SEARCH_IMAGE_URI, Uri::class.java),
        )
        TemplateRoute.COMMENTS -> commentsFragment(intent)
        // V3 / MD3-E 重做版，替代 legacy FragmentLocalUsers（当前账号 hero 卡 +
        // 其他账号分段行 + 添加账号独行）
        TemplateRoute.ACCOUNT_SWITCH -> AccountSwitchV3Fragment()
        TemplateRoute.BOOKED_TAG_FILTER -> BookedTagFeedFragment.newInstance(
            intent.getIntExtra(Params.DATA_TYPE, 0),
            intent.getStringExtra(TemplateActivity.EXTRA_KEYWORD),
        )
        TemplateRoute.ABOUT -> FragmentAboutApp()
        // 统一路由到新的 V3 下载管理页（默认进队列 tab）
        TemplateRoute.BULK_DOWNLOAD_QUEUE -> DownloadManagerV3Fragment()
        TemplateRoute.BULK_SELECT -> BulkSelectV3Fragment.newInstance(
            intent.getStringExtra(BulkSelectHandoff.ARG_HANDOFF_KEY),
        )
        TemplateRoute.NOVEL_BULK_SELECT -> NovelBulkSelectV3Fragment.newInstance(
            intent.getStringExtra(BulkSelectHandoff.ARG_HANDOFF_KEY),
        )
        TemplateRoute.WALKTHROUGH -> WalkthroughFeedFragment()
        TemplateRoute.FOLLOWING -> FollowUserFeedFragment.newInstance(
            Params.getUserId(intent),
            Params.TYPE_PUBLIC,
            true,
        )
        // legacy 直接从 Activity 的 intent 读 USER_ID（它没有 newInstance），
        // 新版收进 arguments，故这里显式传。
        TemplateRoute.NICE_FRIENDS -> NiceFriendFeedFragment.newInstance(Params.getUserId(intent))
        TemplateRoute.NICE_FRIEND_ILLUSTS -> NiceFriendIllustFeedFragment()
        TemplateRoute.SEARCH -> FragmentSearch()
        TemplateRoute.USER_INFO -> FragmentUserInfo()
        TemplateRoute.NEW_WORKS -> FragmentNew()
        TemplateRoute.FANS -> UserFansFeedFragment.newInstance(Params.getUserId(intent))
        TemplateRoute.ILLUST_LIKERS -> LikeUsersFeedFragment.newInstance(
            intent.requireSerializable(Params.CONTENT, Illust::class.java),
        )
        TemplateRoute.NOVEL_LIKERS -> LikeUsersFeedFragment.newInstance(
            intent.getLongExtra(Params.NOVEL_ID, 0L),
            intent.getStringExtra(Params.TITLE),
        )
        // Legacy 路由——桥接到新页 NovelSeriesFragment。保留只为兼容外部深链或仍在路上的
        // 字符串拼接调用；内部入口都已迁到 NOVEL_SERIES + ARG_SERIES_ID(Long)。
        TemplateRoute.NOVEL_SERIES_DETAIL_LEGACY -> NovelSeriesFragment.newInstance(
            intent.getLongExtra(
                NovelSeriesFragment.ARG_SERIES_ID,
                intent.getIntExtra(Params.ID, 0).toLong(),
            ),
        )
        TemplateRoute.USER_ILLUSTS -> UserIllustFeedFragment.newInstance(
            Params.getUserId(intent),
            true,
            intent.getIntExtra(Params.INITIAL_OFFSET, 0),
            intent.getStringExtra(Params.TARGET_DATE),
        )
        // issue #569: 按 Tag 筛选画师插画
        TemplateRoute.USER_ILLUSTS_BY_TAG -> UserIllustByTagFeedFragment.newInstance(
            Params.getUserId(intent),
            intent.getStringExtra(Params.KEY_WORD),
        )
        // issue #996: 按 Tag 筛选画师漫画(与插画同页,网页端点段不同)
        TemplateRoute.USER_MANGA_BY_TAG -> UserIllustByTagFeedFragment.newInstance(
            Params.getUserId(intent),
            intent.getStringExtra(Params.KEY_WORD),
            UserTagSearchSheet.CATEGORY_MANGA,
        )
        // issue #996: 按 Tag 筛选作者小说
        TemplateRoute.USER_NOVELS_BY_TAG -> UserNovelByTagFeedFragment.newInstance(
            Params.getUserId(intent),
            intent.getStringExtra(Params.KEY_WORD),
        )
        TemplateRoute.REQUEST_PLAN_DETAIL -> RequestPlanDetailFragment.newInstance(
            intent.requireSerializable(Params.CONTENT, RequestPlan::class.java),
            IntentCompat.getSerializableExtra(intent, Params.USER_MODEL, User::class.java),
        )
        TemplateRoute.USER_MANGA -> UserMangaFeedFragment.newInstance(
            Params.getUserId(intent),
            true,
            intent.getIntExtra(Params.INITIAL_OFFSET, 0),
            intent.getStringExtra(Params.TARGET_DATE),
        )
        // STAR_TYPE / KEY_WORD 可选：同义词词典管理页跳转时带上（issue #904）
        TemplateRoute.ILLUST_BOOKMARKS -> LikeIllustFeedFragment.newInstance(
            Params.getUserId(intent),
            intent.getStringExtra(Params.STAR_TYPE) ?: Params.TYPE_PUBLIC,
            true,
            intent.getStringExtra(Params.KEY_WORD),
        )
        TemplateRoute.DOWNLOAD_MANAGER -> DownloadManagerV3Fragment()
        TemplateRoute.RECOMMENDED_MANGA -> RecmdMangaFeedFragment.newInstance()
        TemplateRoute.RECOMMENDED_NOVELS -> FragmentNewNovel()
        TemplateRoute.NOVEL_BOOKMARKS -> LikeNovelFeedFragment.newInstance(
            Params.getUserId(intent),
            intent.getStringExtra(Params.STAR_TYPE) ?: Params.TYPE_PUBLIC,
            true,
            intent.getStringExtra(Params.KEY_WORD),
        )
        TemplateRoute.USER_NOVELS -> UserNovelFeedFragment.newInstance(Params.getUserId(intent))
        TemplateRoute.NOVEL_DETAIL -> {
            val bean = IntentCompat.getSerializableExtra(intent, Params.CONTENT, Novel::class.java)
            NovelTextFragment.newInstance(bean?.id ?: intent.getLongExtra(Params.NOVEL_ID, 0L))
        }
        TemplateRoute.NOVEL_READER -> {
            val localUri = intent.getStringExtra(Params.LOCAL_TXT_URI)
            val bean = IntentCompat.getSerializableExtra(intent, Params.CONTENT, Novel::class.java)
            when {
                !localUri.isNullOrEmpty() -> NovelReaderV3Fragment.newInstanceLocal(
                    localUri,
                    intent.getStringExtra(Params.LOCAL_TXT_TITLE),
                    intent.getStringExtra(Params.LOCAL_TXT_KEY),
                )
                bean != null -> NovelReaderV3Fragment.newInstance(bean)
                else -> NovelReaderV3Fragment.newInstance(intent.getLongExtra(Params.NOVEL_ID, 0L))
            }
        }
        TemplateRoute.LOCAL_NOVEL_LIBRARY -> LocalLibraryFragment()
        TemplateRoute.COMIC_READER -> {
            var iid = intent.getLongExtra(Params.ILLUST_ID, 0L)
            if (iid == 0L) iid = intent.getIntExtra(Params.ILLUST_ID, 0).toLong()
            ComicReaderV3Fragment.newInstance(iid)
        }
        TemplateRoute.NOVEL_SERIES -> NovelSeriesFragment.newInstance(
            intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID, 0L),
        )
        TemplateRoute.WEB_HOME -> StreetMainFragment()
        TemplateRoute.WEB_PAGE -> WebFragment.newInstance(
            intent.getStringExtra(Params.URL) ?: "https://www.pixiv.net/",
            intent.getBooleanExtra("saveCookies", false),
        )
        TemplateRoute.IMAGE_DETAIL -> FragmentImageDetail.newInstance(
            intent.getStringExtra(Params.URL),
            intent.getStringExtra(Params.TITLE),
        )
        TemplateRoute.UPSCALE_COMPARE -> UpscaleCompareFragment.newInstance(
            intent.requireString("upscaled_path"),
            intent.requireString("original_path"),
        )
        TemplateRoute.AI_UPSCALE -> FragmentAiUpscale()
        TemplateRoute.REMBG_HIGHLIGHT -> RembgHighlightFragment.newInstance(
            intent.requireString("original_path"),
            intent.requireString("rembg_path"),
        )
        TemplateRoute.REMBG_PREVIEW -> RembgPreviewFragment.newInstance(intent.requireString("rembg_path"))
        TemplateRoute.REMBG_MODEL_DOWNLOAD -> RembgModelDownloadFragment.newInstance(
            intent.requireString("model_name"),
        )
        TemplateRoute.TRANSLATION_MODEL_DOWNLOAD -> TranslationModelDownloadFragment.newInstance(
            intent.requireString("translation_model_name"),
        )
        TemplateRoute.RIFE_MODEL_DOWNLOAD -> RifeDownloadFragment.newInstance(
            intent.requireString("rife_model_name"),
        )
        TemplateRoute.MANGA_OCR_MODEL_DOWNLOAD -> MangaOcrDownloadFragment.newInstance(
            intent.requireString("manga_ocr_model_name"),
        )
        TemplateRoute.COMIC_TEXT_DETECTOR_MODEL_DOWNLOAD -> ComicTextDetectorDownloadFragment.newInstance(
            intent.requireString("ctd_model_name"),
        )
        TemplateRoute.EDIT_ACCOUNT -> FragmentEditAccount()
        // V3 账号备份/恢复页（pixshaft-api /v1/account/*）。与 Pixiv 原生「绑定邮箱」
        // (改 pixiv-id/邮箱/密码) 是两回事。mode = "backup"(设置入口) | "restore"(登录页入口)。
        TemplateRoute.EMAIL_BACKUP -> EmailBackupV3Fragment().apply {
            intent.getStringExtra(EmailBackupV3Fragment.ARG_MODE)?.let { mode ->
                arguments = bundleOf(EmailBackupV3Fragment.ARG_MODE to mode)
            }
        }
        TemplateRoute.EDIT_PROFILE -> FragmentEditFile()
        TemplateRoute.MUTED_TAGS -> FragmentViewPager.newInstance(Params.VIEW_PAGER_MUTED)
        TemplateRoute.FILE_NAME_FORMAT -> FragmentFileName.newInstance()
        TemplateRoute.DOWNLOAD_PATH_SETTINGS -> DownloadPathSettingsFragment()
        TemplateRoute.ARIA2_SETTINGS -> Aria2SettingsFragment()
        TemplateRoute.AI_TRANSLATE_SETTINGS -> AiTranslateSettingsFragment()
        TemplateRoute.NOVEL_HEADER_SETTINGS -> NovelHeaderSettingsFragment()
        TemplateRoute.DONATE -> FragmentDonate.newInstance()
        // feeds 版(替代 legacy FragmentNewNovels);独立页带 toolbar,restrict 走默认「全部」
        TemplateRoute.FOLLOWING_NOVELS -> FollowingNovelFeedFragment.newInstance()
        TemplateRoute.USER_MANGA_SERIES -> UserMangaSeriesFeedFragment.newInstance(Params.getUserId(intent))
        // V3 漫画系列详情页 IllustSeriesFragment。系列 id 兼容旧调用的 MANGA_SERIES_ID(int)
        // 与新 ARG_SERIES_ID(long)。
        TemplateRoute.MANGA_SERIES_DETAIL -> {
            var sid = intent.getLongExtra(IllustSeriesFragment.ARG_SERIES_ID, 0L)
            if (sid == 0L) sid = intent.getIntExtra(Params.MANGA_SERIES_ID, 0).toLong()
            if (sid == 0L) sid = intent.getIntExtra(Params.ID, 0).toLong()
            IllustSeriesFragment.newInstance(sid)
        }
        TemplateRoute.USER_NOVEL_SERIES -> UserNovelSeriesFeedFragment.newInstance(Params.getUserId(intent))
        TemplateRoute.FEATURE_LIST -> FeatureFeedFragment()
        TemplateRoute.WORKSPACE -> FragmentWorkSpace()
        TemplateRoute.PRIME_TAGS -> PrimeTagsFragment()
        TemplateRoute.PINNED_CONTENT -> PinnedTabsFragment()
        // key = 老 assets 文件名里那段 sha256，现在是 pixshaft-api 的路径参数。
        TemplateRoute.PRIME_TAG_DETAIL -> PrimeTagDetailFragment.newInstance(
            intent.requireString("name"),
            intent.requireString("key"),
        )
        // 「我的插画收藏」有两种落点：本地镜像已经完整同步过一次 → 直接进本地库
        //（能倒序、能按标签/作者/年份筛，而服务端接口给不了这些）；还没同步完 → 老的双 tab 页。
        // 带 Params.FLAG 的 intent 是本地库自己的「原始收藏列表」入口发来的，必须原样给老页面，
        // 否则用户从库里点进去会被立刻重定向回来，两个页面互相踢皮球。
        TemplateRoute.MY_ILLUST_COLLECTION -> {
            val wantsClassic = intent.getBooleanExtra(ceui.lisa.utils.Params.FLAG, false)
            if (!wantsClassic && isBookmarkMirrorReady(ceui.pixiv.db.mirror.MirrorContentType.ILLUST)) {
                ceui.pixiv.ui.library.BookmarkLibraryFragment.newInstance()
            } else {
                FragmentCollection.newInstance(0)
            }
        }
        // 小说收藏入口与插画侧同一条规则（理由见上面 MY_ILLUST_COLLECTION 的注释）。
        TemplateRoute.MY_NOVEL_COLLECTION -> {
            val wantsClassic = intent.getBooleanExtra(ceui.lisa.utils.Params.FLAG, false)
            if (!wantsClassic && isBookmarkMirrorReady(ceui.pixiv.db.mirror.MirrorContentType.NOVEL)) {
                ceui.pixiv.ui.library.NovelBookmarkLibraryFragment.newInstance()
            } else {
                FragmentCollection.newInstance(1)
            }
        }
        // 收藏库：按 contentType 分流到插画版 / 小说版（两者页面接线共用 BookmarkLibraryUi，
        // 只是列表基类和卡片不同）。restrict 决定落在公开还是悄悄那个书架上。
        TemplateRoute.BOOKMARK_LIBRARY -> {
            val starType = intent.getStringExtra(ceui.lisa.utils.Params.STAR_TYPE)
                ?: ceui.lisa.utils.Params.TYPE_PUBLIC
            val type = ceui.pixiv.db.mirror.MirrorContentType.of(
                intent.getIntExtra(ceui.pixiv.ui.library.BookmarkLibraryUi.ARG_CONTENT_TYPE, 0)
            ) ?: ceui.pixiv.db.mirror.MirrorContentType.ILLUST
            if (type == ceui.pixiv.db.mirror.MirrorContentType.NOVEL) {
                ceui.pixiv.ui.library.NovelBookmarkLibraryFragment.newInstance(starType = starType)
            } else {
                ceui.pixiv.ui.library.BookmarkLibraryFragment.newInstance(starType = starType)
            }
        }
        TemplateRoute.WATCHLIST -> FragmentCollection.newInstance(3)
        TemplateRoute.MY_FOLLOWING -> FragmentCollection.newInstance(2)
        TemplateRoute.NOVEL_MARKERS -> NovelMarkersFeedFragment()
        // 设置 → 标签译文颜色 也复用本页，通过 extra 切成选择器模式。
        TemplateRoute.THEME_COLOR -> ThemeColorFeedFragment.newInstance(
            intent.getBooleanExtra(ThemeColorFeedFragment.ARG_SELECT_TAG_TRANSLATION_COLOR, false),
        )
        TemplateRoute.SAF_TEST -> FragmentSAF()
        // flagObjectId 是插画/用户等真实业务 ID，必须走 long——曾经这里用 getIntExtra 读，
        // 跟 Illust.id(Long) 类型不匹配，Bundle 类型不符时静默返回 0，导致举报的 illust_id 恒为 0。
        TemplateRoute.FLAG_REASON -> FlagReasonFragment.newInstance(
            intent.getLongExtra(FlagDescFragment.FlagObjectIdKey, 0L),
            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0),
        )
        TemplateRoute.FLAG_DESC -> FlagDescFragment.newInstance(
            intent.getIntExtra(FlagDescFragment.FlagTopicIdKey, 0),
            intent.getStringExtra(FlagDescFragment.FlagTopicTitleKey),
            intent.getLongExtra(FlagDescFragment.FlagObjectIdKey, 0L),
            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0),
        )
        TemplateRoute.RELATED_USERS -> RelatedUserFeedFragment.newInstance(Params.getUserId(intent))
        TemplateRoute.MARKDOWN -> FragmentMarkdown.newInstance(intent.getStringExtra(Params.URL))
        TemplateRoute.VERSION_HISTORY -> FragmentVersionHistory()
        TemplateRoute.DISCOVERY -> DiscoveryFeedFragment()
        TemplateRoute.RECENT_RECOMMEND -> FragmentRecentRecommend()
        TemplateRoute.SITE_RECOMMEND -> FragmentSiteRecommend()
        TemplateRoute.ARTIST_RANK -> ArtistRankFeedFragment.newInstance("total")
        TemplateRoute.ARTIST_AVG_RANK -> ArtistRankFeedFragment.newInstance("avg")
        TemplateRoute.VIEW_RANK -> ViewRankFragment.newInstance()
        TemplateRoute.PIXIV_COMIC -> ComicTopFeedFragment()
        TemplateRoute.FANBOX_HOME -> FanboxHomeFragment()
        TemplateRoute.FANBOX_POST -> FanboxPostDetailFragment.newInstance(
            intent.requireString(FanboxPostDetailFragment.ARG_POST_ID),
        )
        TemplateRoute.BOOKMARK_RANK -> BookmarkRankFragment.newInstance(null, null)
        // 同一个 Fragment,多带一个 ?ai=only
        TemplateRoute.AI_RANK -> BookmarkRankFragment.newInstance(AI_ONLY, null)
        // 同一个 Fragment,多带一个 ?restrict=sfw(服务端剔除 R-18)
        TemplateRoute.SFW_RANK -> BookmarkRankFragment.newInstance(null, RESTRICT_SFW)
        TemplateRoute.YEAR_RANK -> YearRankFragment.newInstance()
        TemplateRoute.TAG_RANK -> TagRankFragment.newInstance()
        TemplateRoute.WALLPAPER_RANK -> WallpaperRankFragment.newInstance()
        // shaft-api-v2 discover/series:漫画 / 小说系列按累计收藏排
        TemplateRoute.SERIES_RANK -> SeriesRankFragment.newInstance()
        // shaft-api-v2 discover/most-bookmarked?month=YYYY-MM,选月份 + 三类型 tab
        TemplateRoute.MONTH_RANK -> MonthRankFragment.newInstance()
        // shaft-api-v2 discover/most-bookmarked?type=novel&length=long|medium|short
        TemplateRoute.NOVEL_LENGTH_RANK -> NovelLengthRankFragment.newInstance()
        TemplateRoute.TRENDING_ARTISTS -> TrendingArtistsFragment.newInstance()
        TemplateRoute.UGOIRA_RANK -> UgoiraRankFragment.newInstance()
        TemplateRoute.EVENT_HISTORY -> FragmentEventHistory()
        TemplateRoute.DEBUG_BULK_DOWNLOAD -> BulkDownloadDebugFragment()
        TemplateRoute.DEBUG_SAF_PERF -> SafPerfTestFragment()
        TemplateRoute.DEBUG_NETWORK_TEST -> NetworkTestFragment()
        TemplateRoute.DEBUG_POPULAR_TAG_EXPORT -> PopularTagExportFragment()
        // 同义词词典管理页（issue #904 按标签收藏优化）
        TemplateRoute.SYNONYM_DICT -> SynonymDictFragment()
        // peer_uid > 0 → 与该 pixiv 用户 1v1；否则 → 会话列表（全员公屏 + 本地碰过的 1v1）。
        // 列表行点进来会带 peer_uid 再走一次这里，所以单聊页的打开方式和以前完全一样。
        TemplateRoute.CHAT -> {
            val peerUid = intent.getLongExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, 0L)
            if (peerUid > 0L) DemoChatListFragment.newInstanceForPeer(peerUid) else ChatRoomListFragment()
        }
        // 显式「打开全员公屏」入口：会话列表点 Global 行用。不带 peer_uid 走 CHAT 会回到列表本身。
        TemplateRoute.CHAT_GLOBAL_ROOM -> DemoChatListFragment()
        TemplateRoute.PLAZA -> PlazaFragment()
        // 从插画 V3「分享至广场」入口进来会带 ILLUST_ID,需透传给 compose fragment 预附这张 illust;
        // 广场右上「+」入口不带,走空白编辑器。
        TemplateRoute.PLAZA_COMPOSE -> PlazaComposeFragment().apply {
            val prefillIllustId = intent.getLongExtra(PlazaComposeFragment.ARG_PREFILL_ILLUST_ID, 0L)
            if (prefillIllustId > 0L) {
                arguments = bundleOf(PlazaComposeFragment.ARG_PREFILL_ILLUST_ID to prefillIllustId)
            }
        }
        // 从广场卡片点 illust 缩略走这条;只带 ILLUST_ID, ArtworkV3ViewModel 自己按 id lazy load。
        TemplateRoute.PLAZA_OPEN_ILLUST -> ArtworkV3Fragment.newInstance(intent.getIntExtra(Params.ILLUST_ID, 0))
        TemplateRoute.PLAZA_POST_DETAIL -> PlazaPostDetailFragment.newInstance(
            intent.getLongExtra(PlazaPostDetailFragment.EXTRA_POST_ID, 0L),
        )
        TemplateRoute.NOTIFICATION_CENTER -> NotificationPagerFragment()
        TemplateRoute.NOTIFICATION_VIEW_MORE -> NotificationViewMoreFragment()
        TemplateRoute.INFO_CATEGORY -> InfoCategoryListFragment()
        TemplateRoute.SNAPSHOT_MANAGER -> SnapshotManagerFragment()
        TemplateRoute.SNAPSHOT_VIEW -> ArtworkV3Fragment.newInstanceSnapshot(
            intent.requireString(SnapshotManagerFragment.ARG_SNAPSHOT_ID),
            intent.getBooleanExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false),
        )
        TemplateRoute.SNAPSHOT_VIEW_CLASSIC -> FragmentIllust().apply {
            arguments = bundleOf(
                "illust_id" to 0,
                SnapshotManagerFragment.ARG_SNAPSHOT_ID to
                    intent.getStringExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID),
                SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO to
                    intent.getBooleanExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false),
            )
        }
        TemplateRoute.SNAPSHOT_COMMENTS -> CommentsFragment().apply {
            arguments = bundleOf(
                "objectId" to intent.getLongExtra("objectId", 0L),
                "objectArthurId" to intent.getLongExtra("objectArthurId", 0L),
                "objectType" to intent.getStringExtra("objectType"),
                SnapshotManagerFragment.ARG_SNAPSHOT_ID to
                    intent.getStringExtra(SnapshotManagerFragment.ARG_SNAPSHOT_ID),
                SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO to
                    intent.getBooleanExtra(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false),
            )
        }
    }

    private fun commentsFragment(intent: Intent): CommentsFragment {
        val illustId = intent.getIntExtra(Params.ILLUST_ID, 0)
        if (illustId != 0) {
            val hit: Illust? = ObjectPool.getIllust(illustId.toLong()).value
            return CommentsFragment.newInstance(illustId.toLong(), hit?.user?.id ?: 0L, ObjectType.ILLUST)
        }
        val novelId = intent.getIntExtra(Params.NOVEL_ID, 0)
        val hit: Novel? = ObjectPool.getNovel(novelId.toLong()).value
        return CommentsFragment.newInstance(novelId.toLong(), hit?.user?.id ?: 0L, ObjectType.NOVEL)
    }

    /** 目标页的 newInstance 参数非空；缺 extra 是调用方的 bug，报清楚是哪个 key 而不是裸 NPE。 */
    private fun Intent.requireString(key: String): String =
        requireNotNull(getStringExtra(key)) { "TemplateActivity route requires string extra '$key'" }

    private fun <T : java.io.Serializable> Intent.requireSerializable(key: String, clazz: Class<T>): T =
        requireNotNull(IntentCompat.getSerializableExtra(this, key, clazz)) {
            "TemplateActivity route requires ${clazz.simpleName} extra '$key'"
        }
}

/**
 * 当前账号在这个内容类型下的「公开收藏」在本地镜像里是不是已经完整了。
 *
 * 是一次主键点查（表里最多四行），够便宜到可以摆在导航路径上；任何异常都按「没就绪」处理，
 * 让入口回落到原始列表 —— 导航绝不能因为一个附加功能而崩。判据用**公开**书架：收藏库
 * 默认落在它上面，悄悄收藏那半边进去以后可以就地切。
 */
private fun isBookmarkMirrorReady(contentType: ceui.pixiv.db.mirror.MirrorContentType): Boolean {
    val uid = ceui.pixiv.session.SessionManager.loggedInUid
    if (uid <= 0L) return false
    return ceui.lisa.activities.Shaft.getContext().appServices().bookmarkMirror.isShelfReady(
        ceui.pixiv.db.mirror.BookmarkShelf(
            ownerUid = uid,
            contentType = contentType,
            restrict = ceui.pixiv.db.mirror.MirrorRestrict.PUBLIC,
        )
    )
}
