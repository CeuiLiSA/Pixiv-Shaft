package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.content.IntentCompat;

import java.util.HashSet;
import java.util.Set;

import com.jaredrummler.android.colorpicker.ColorPickerDialogListener;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.lisa.fragments.FragmentAboutApp;
import ceui.pixiv.ui.collection.BookedTagFeedFragment;
import ceui.lisa.fragments.FragmentCollection;
import ceui.lisa.fragments.FragmentDonate;
import ceui.lisa.fragments.FragmentEditAccount;
import ceui.lisa.fragments.FragmentEditFile;
import ceui.pixiv.ui.feature.FeatureFeedFragment;
import ceui.lisa.fragments.FragmentFileName;
import ceui.lisa.fragments.FragmentHistoryTabs;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.pixiv.ui.user.LikeUsersFeedFragment;
import ceui.lisa.fragments.FragmentLogin;
import ceui.pixiv.ui.user.UserMangaSeriesFeedFragment;
import ceui.lisa.fragments.FragmentMarkdown;
import ceui.lisa.fragments.FragmentNew;
import ceui.lisa.fragments.FragmentNewNovel;
import ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment;
import ceui.pixiv.ui.comic.reader.ComicReaderV3Fragment;
import ceui.pixiv.ui.novel.NovelSeriesFragment;
import ceui.pixiv.ui.novel.NovelTextFragment;
import ceui.pixiv.ui.novel.NovelMarkersFeedFragment;
import ceui.pixiv.ui.user.UserNovelSeriesFeedFragment;
import ceui.lisa.fragments.FragmentPv;
import ceui.pixiv.ui.detail.RelatedIllustFeedFragment;
import ceui.pixiv.ui.user.RelatedUserFeedFragment;
import ceui.lisa.fragments.FragmentSAF;
import ceui.lisa.fragments.FragmentSearch;
import ceui.lisa.fragments.FragmentSettingsHub;
import ceui.lisa.fragments.SettingsCatalog;
import ceui.pixiv.ui.user.UserIllustByTagFeedFragment;
import ceui.lisa.fragments.FragmentUserInfo;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.lisa.fragments.FragmentWebView;
import ceui.lisa.fragments.FragmentWorkSpace;
import ceui.loxia.Illust;
import ceui.loxia.Novel;
import ceui.loxia.User;
import ceui.lisa.utils.Params;
import ceui.loxia.ObjectPool;
import ceui.loxia.ObjectType;
import ceui.loxia.flag.FlagDescFragment;
import ceui.loxia.flag.FlagReasonFragment;
import ceui.pixiv.ui.comments.CommentsFragment;
import ceui.pixiv.ui.notification.InfoCategoryListFragment;
import ceui.pixiv.ui.notification.NotificationPagerFragment;
import ceui.pixiv.ui.notification.NotificationViewMoreFragment;
import ceui.pixiv.ui.prime.PrimeTagDetailFragment;
import ceui.pixiv.ui.prime.PrimeTagsFragment;
import ceui.pixiv.ui.settings.ThemeColorFeedFragment;

public class TemplateActivity extends BaseActivity<ActivityFragmentBinding> implements ColorPickerDialogListener {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    /** For dataType=="聊天室": pixiv uid of the chat peer for 1v1. Absent / 0 = global room. */
    public static final String EXTRA_CHAT_PEER_UID = "chatPeerUid";
    protected Fragment childFragment;
    private String dataType;
    // 阅读器消费过 ACTION_DOWN 的音量键 keyCode 集合，待配对吃掉对应 ACTION_UP (issue #874)。
    // 用 Set 而非单个 int，是因为 VOL_UP / VOL_DOWN 可能近乎同时按下，单字段会被后者覆盖导致前者 UP 泄漏到系统。
    private final Set<Integer> volumeKeysAwaitingUp = new HashSet<>(2);

    @Override
    protected void initBundle(Bundle bundle) {
        dataType = bundle.getString(EXTRA_FRAGMENT);
    }

    protected Fragment createNewFragment() {
        Intent intent = getIntent();
        if (!TextUtils.isEmpty(dataType)) {
            switch (dataType) {
                case "登录注册":
                    return new FragmentLogin();
                case "相关作品": {
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(Params.ILLUST_TITLE);
                    return RelatedIllustFeedFragment.newInstance(id, title);
                }
                case "浏览记录":
                    return new FragmentHistoryTabs();
                case "稍后再看":
                    return new ceui.pixiv.ui.watchlater.WatchLaterTabsFragment();
                case "网页链接": {
                    String url = intent.getStringExtra(Params.URL);
                    String title = intent.getStringExtra(Params.TITLE);
                    boolean preferPreserve = intent.getBooleanExtra(Params.PREFER_PRESERVE, false);
                    return FragmentWebView.newInstance(title, url, preferPreserve);
                }
                case "借号用量":
                    return new ceui.pixiv.ui.usage.Nana7miUsageFragment();
                case "设置":
                    return new FragmentSettingsHub();
                case "设置分类":
                    return SettingsCatalog.fragmentFor(
                            intent.getStringExtra(SettingsCatalog.EXTRA_CATEGORY));
                case "推荐用户":
                    // 与 FragmentRight#seeMore 配对：货架把自己那批快照存进 RecmdUserHandoff，
                    // 只把 key 传过来。取用/清理都归 RecmdUserFeedFragment 自己（数据落进 VM，
                    // 旋转不重拉；key 为 null 或 map 已失效时它自己退化成网络首屏）。
                    return ceui.pixiv.ui.user.RecmdUserFeedFragment.newInstance(
                            intent.getStringExtra(Params.USER_MODEL));
                case "特辑":
                    return new FragmentPv();
                case "以图搜图": {
                    // 搜索本身由 WebView 发（引擎都在 Cloudflare 质询后面，见 ReverseImage），
                    // 这里只是把引擎上传页和待搜图片转交过去。
                    String engineUrl = intent.getStringExtra(Params.URL);
                    String engineName = intent.getStringExtra(Params.TITLE);
                    Uri imageUri = IntentCompat.getParcelableExtra(
                            intent, Params.REVERSE_SEARCH_IMAGE_URI, Uri.class);
                    return FragmentWebView.newInstance(engineName, engineUrl, imageUri);
                }
                case "相关评论": {
                    return getCommentsFragment(intent);
                }
                case "账号管理":
                    // V3 / MD3-E 重做版，替代 legacy FragmentLocalUsers（当前账号 hero 卡 +
                    // 其他账号分段行 + 添加账号独行）
                    return new ceui.pixiv.ui.account.AccountSwitchV3Fragment();
                case "按标签筛选": {
                    return BookedTagFeedFragment.newInstance(intent.getIntExtra(Params.DATA_TYPE, 0), intent.getStringExtra(EXTRA_KEYWORD));
                }
                // 「按标签收藏」不再走整页路由 —— 从右侧 push 进来一张 activity 对「就地收藏」
                // 这种轻动作太重。现在由 ceui.pixiv.ui.bookmark.SelectTagBottomSheet 以 MD3
                // bottom sheet 弹出，SelectTagFeedFragment 退化成它的列表 child。
                case "关于软件":
                    return new FragmentAboutApp();
                case "批量下载队列":
                    // 统一路由到新的 V3 下载管理页（默认进队列 tab）
                    return new ceui.pixiv.ui.download.DownloadManagerV3Fragment();
                case "批量选择":
                    return ceui.pixiv.ui.bulk.BulkSelectV3Fragment.newInstance(
                            intent.getStringExtra(ceui.pixiv.ui.bulk.BulkSelectHandoff.ARG_HANDOFF_KEY));
                case "小说批量选择":
                    return ceui.pixiv.ui.bulk.NovelBulkSelectV3Fragment.newInstance(
                            intent.getStringExtra(ceui.pixiv.ui.bulk.BulkSelectHandoff.ARG_HANDOFF_KEY));
                case "画廊":
                    // feeds 框架版画廊，替代 legacy FragmentWalkThrough
                    return new ceui.pixiv.ui.home.WalkthroughFeedFragment();
                case "正在关注":
                    // feeds 框架版，替代 legacy FragmentFollowUser + FollowUserRepo + UAdapter
                    return ceui.pixiv.ui.user.FollowUserFeedFragment.newInstance(
                            Params.getUserId(getIntent()),
                            Params.TYPE_PUBLIC, true);
                case "好P友":
                    // feeds 框架版，替代 legacy FragmentNiceFriend + NiceFriendRepo + UAdapter。
                    // legacy 直接从 Activity 的 intent 读 USER_ID（它没有 newInstance），
                    // 新版收进 arguments，故这里显式传。
                    return ceui.pixiv.ui.user.NiceFriendFeedFragment.newInstance(
                            Params.getUserId(intent));
                case "好P友作品":
                    // feeds 框架版好P友作品流，替代 legacy FragmentNiceFriendIllust
                    return new ceui.pixiv.ui.home.NiceFriendIllustFeedFragment();
                case "搜索":
                    return new FragmentSearch();
                case "详细信息":
                    return new FragmentUserInfo();
                case "最新作品":
                    return new FragmentNew();
                case "粉丝":
                    // feeds 框架版，替代 legacy FragmentWhoFollowThisUser + WhoFollowThisUserRepo + UAdapter
                    return ceui.pixiv.ui.user.UserFansFeedFragment.newInstance(
                            Params.getUserId(intent));
                case "喜欢这个作品的用户":
                    return LikeUsersFeedFragment.newInstance((Illust) intent.getSerializableExtra(Params.CONTENT));
                case "喜欢这部小说的用户":
                    return LikeUsersFeedFragment.newInstance(
                            intent.getLongExtra(Params.NOVEL_ID, 0L),
                            intent.getStringExtra(Params.TITLE));
                case "小说系列详情": {
                    // Legacy 路由——桥接到新页 NovelSeriesFragment。保留 case
                    // 兼容外部深链或仍在路上的字符串拼接调用；内部入口都已迁到
                    // "小说系列" + ARG_SERIES_ID(Long)。
                    long sid = intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID,
                            intent.getIntExtra(Params.ID, 0));
                    return NovelSeriesFragment.Companion.newInstance(sid);
                }
                case "插画作品":
                    return ceui.pixiv.ui.user.UserIllustFeedFragment.newInstance(
                            Params.getUserId(intent),
                            true, intent.getIntExtra(Params.INITIAL_OFFSET, 0),
                            intent.getStringExtra(Params.TARGET_DATE));
                case "插画标签作品": // issue #569: 按 Tag 筛选画师插画
                    return UserIllustByTagFeedFragment.newInstance(Params.getUserId(intent),
                            intent.getStringExtra(Params.KEY_WORD));
                case "漫画标签作品": // issue #996: 按 Tag 筛选画师漫画(与插画同页,网页端点段不同)
                    return UserIllustByTagFeedFragment.newInstance(Params.getUserId(intent),
                            intent.getStringExtra(Params.KEY_WORD),
                            ceui.pixiv.ui.user.UserTagSearchSheet.CATEGORY_MANGA);
                case "小说标签作品": // issue #996: 按 Tag 筛选作者小说
                    return ceui.pixiv.ui.user.UserNovelByTagFeedFragment.newInstance(
                            Params.getUserId(intent),
                            intent.getStringExtra(Params.KEY_WORD));
                case "约稿方案详情":
                    return ceui.pixiv.ui.user.RequestPlanDetailFragment.newInstance(
                            (ceui.pixiv.ui.user.RequestPlan) intent.getSerializableExtra(Params.CONTENT),
                            (ceui.loxia.User) intent.getSerializableExtra(Params.USER_MODEL));
                case "漫画作品":
                    // feeds 框架版,替代 legacy FragmentUserManga
                    return ceui.pixiv.ui.user.UserMangaFeedFragment.newInstance(
                            Params.getUserId(intent),
                            true, intent.getIntExtra(Params.INITIAL_OFFSET, 0),
                            intent.getStringExtra(Params.TARGET_DATE));
                case "插画/漫画收藏": {
                    // STAR_TYPE / KEY_WORD 可选：同义词词典管理页跳转时带上（issue #904）
                    String starType = intent.getStringExtra(Params.STAR_TYPE);
                    return ceui.pixiv.ui.collection.LikeIllustFeedFragment.newInstance(
                            Params.getUserId(intent),
                            starType != null ? starType : Params.TYPE_PUBLIC, true,
                            intent.getStringExtra(Params.KEY_WORD));
                }
                case "下载管理":
                    return new ceui.pixiv.ui.download.DownloadManagerV3Fragment();
                case "推荐漫画":
                    return ceui.pixiv.ui.home.RecmdMangaFeedFragment.newInstance();
                case "推荐小说":
                    return new FragmentNewNovel();
                case "小说收藏": {
                    // STAR_TYPE / KEY_WORD 可选：同义词词典管理页跳转时带上（issue #904）
                    String novelStarType = intent.getStringExtra(Params.STAR_TYPE);
                    // feeds 框架版，替代 legacy FragmentLikeNovel + LikeNovelRepo + NAdapter
                    return ceui.pixiv.ui.collection.LikeNovelFeedFragment.newInstance(
                            Params.getUserId(intent),
                            novelStarType != null ? novelStarType : Params.TYPE_PUBLIC, true,
                            intent.getStringExtra(Params.KEY_WORD));
                }
                case "小说作品":
                    // feeds 框架版,替代 legacy FragmentUserNovel
                    return ceui.pixiv.ui.user.UserNovelFeedFragment.newInstance(
                            Params.getUserId(intent));
                case "小说详情": {
                    Novel bean = (Novel) intent.getSerializableExtra(Params.CONTENT);
                    long tid = bean != null ? bean.getId() : intent.getLongExtra(Params.NOVEL_ID, 0L);
                    return NovelTextFragment.Companion.newInstance(tid);
                }
                case "小说正文": {
                    String localUri = intent.getStringExtra(Params.LOCAL_TXT_URI);
                    if (localUri != null && !localUri.isEmpty()) {
                        return NovelReaderV3Fragment.newInstanceLocal(
                                localUri,
                                intent.getStringExtra(Params.LOCAL_TXT_TITLE),
                                intent.getStringExtra(Params.LOCAL_TXT_KEY));
                    }
                    Novel bean = (Novel) intent.getSerializableExtra(Params.CONTENT);
                    if (bean != null) {
                        return NovelReaderV3Fragment.newInstance(bean);
                    }
                    long rid = intent.getLongExtra(Params.NOVEL_ID, 0L);
                    return NovelReaderV3Fragment.newInstance(rid);
                }
                case "本地小说库":
                    return new ceui.pixiv.ui.novel.local.LocalLibraryFragment();
                case "漫画阅读": {
                    long iid = intent.getLongExtra(Params.ILLUST_ID, 0L);
                    if (iid == 0L) iid = intent.getIntExtra(Params.ILLUST_ID, 0);
                    return ComicReaderV3Fragment.newInstance(iid);
                }
                case "小说系列": {
                    long sid = intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID, 0L);
                    return NovelSeriesFragment.Companion.newInstance(sid);
                }
                case "Web首页":
                    return new ceui.lisa.fragments.StreetMainFragment();
                case "Web页面": {
                    String webUrl = intent.getStringExtra(Params.URL);
                    boolean saveCookies = intent.getBooleanExtra("saveCookies", false);
                    return ceui.pixiv.ui.web.WebFragment.Companion.newInstance(
                            webUrl != null ? webUrl : "https://www.pixiv.net/",
                            saveCookies);
                }
                case "图片详情":
                    return FragmentImageDetail.newInstance(intent.getStringExtra(Params.URL), intent.getStringExtra(Params.TITLE));
                case "画质增强对比":
                    return ceui.pixiv.ui.upscale.UpscaleCompareFragment.newInstance(
                            intent.getStringExtra("upscaled_path"),
                            intent.getStringExtra("original_path"));
                case "AI画质提升":
                    return new ceui.pixiv.ui.upscale.FragmentAiUpscale();
                case "主体高亮":
                    return ceui.pixiv.ui.upscale.RembgHighlightFragment.newInstance(
                            intent.getStringExtra("original_path"),
                            intent.getStringExtra("rembg_path"));
                case "抠图预览":
                    return ceui.pixiv.ui.upscale.RembgPreviewFragment.newInstance(
                            intent.getStringExtra("rembg_path"));
                case "模型下载":
                    return ceui.pixiv.ui.upscale.RembgModelDownloadFragment.newInstance(
                            intent.getStringExtra("model_name"));
                case "翻译模型下载":
                    return ceui.pixiv.ui.translate.TranslationModelDownloadFragment.newInstance(
                            intent.getStringExtra("translation_model_name"));
                case "RIFE补帧模型下载":
                    return ceui.pixiv.ui.interpolate.RifeDownloadFragment.newInstance(
                            intent.getStringExtra("rife_model_name"));
                case "漫画OCR模型下载":
                    return ceui.pixiv.ui.translate.MangaOcrDownloadFragment.newInstance(
                            intent.getStringExtra("manga_ocr_model_name"));
                case "漫画文本框检测模型下载":
                    return ceui.pixiv.ui.translate.ComicTextDetectorDownloadFragment.newInstance(
                            intent.getStringExtra("ctd_model_name"));
                case "绑定邮箱":
                    return new FragmentEditAccount();
                case "邮箱备份": {
                    // V3 账号备份/恢复页（pixshaft-api /v1/account/*）。与上面
                    // Pixiv 原生「绑定邮箱」(改 pixiv-id/邮箱/密码) 是两回事。
                    // mode = "backup"(设置入口) | "restore"(登录页入口)。
                    ceui.pixiv.ui.account.EmailBackupV3Fragment backupFragment =
                            new ceui.pixiv.ui.account.EmailBackupV3Fragment();
                    String mode = intent.getStringExtra(
                            ceui.pixiv.ui.account.EmailBackupV3Fragment.ARG_MODE);
                    if (mode != null) {
                        android.os.Bundle args = new android.os.Bundle();
                        args.putString(
                                ceui.pixiv.ui.account.EmailBackupV3Fragment.ARG_MODE, mode);
                        backupFragment.setArguments(args);
                    }
                    return backupFragment;
                }
                case "编辑个人资料":
                    return new FragmentEditFile();
                case "标签屏蔽记录":
                    return FragmentViewPager.newInstance(Params.VIEW_PAGER_MUTED);
                case "修改命名方式":
                    return FragmentFileName.newInstance();
                case "下载路径与文件名":
                    return new ceui.pixiv.ui.settings.DownloadPathSettingsFragment();
                case "aria2远程下载":
                    return new ceui.pixiv.ui.settings.Aria2SettingsFragment();
                case "自定义AI翻译":
                    return new ceui.pixiv.ui.settings.AiTranslateSettingsFragment();
                case "小说信息头":
                    return new ceui.pixiv.ui.settings.NovelHeaderSettingsFragment();
                case "捐赠":
                    return FragmentDonate.newInstance();
                case "关注者的小说":
                    // feeds 版(替代 legacy FragmentNewNovels);独立页带 toolbar,restrict 走默认「全部」
                    return ceui.pixiv.ui.dynamic.FollowingNovelFeedFragment.newInstance();
                case "漫画系列作品":
                    return UserMangaSeriesFeedFragment.newInstance(Params.getUserId(intent));
                case "漫画系列详情": {
                    // 迁到 V3 漫画系列详情页 IllustSeriesFragment（标题优先的单话列表，
                    // 不再是旧瀑布流）。系列 id 兼容旧调用的 MANGA_SERIES_ID(int) 与
                    // 新 ARG_SERIES_ID(long)。
                    long sid = intent.getLongExtra(
                            ceui.pixiv.ui.detail.IllustSeriesFragment.ARG_SERIES_ID, 0L);
                    if (sid == 0L) sid = intent.getIntExtra(Params.MANGA_SERIES_ID, 0);
                    if (sid == 0L) sid = intent.getIntExtra(Params.ID, 0);
                    return ceui.pixiv.ui.detail.IllustSeriesFragment.Companion.newInstance(sid);
                }
                case "小说系列作品":
                    return UserNovelSeriesFeedFragment.newInstance(Params.getUserId(intent));
                case "精华列":
                    return new FeatureFeedFragment();
                case "我的作业环境":
                    return new FragmentWorkSpace();
                case "PrimeTagsList":
                    return new PrimeTagsFragment();
                case "PinnedTagsList":
                    return new ceui.pixiv.ui.pinned.PinnedTagsFragment();
                case "PrimeTagDetail":
                    // key = 老 assets 文件名里那段 sha256，现在是 pixshaft-api 的路径参数。
                    String primeKey = intent.getStringExtra("key");
                    assert primeKey != null;

                    String name = intent.getStringExtra("name");
                    assert name != null;

                    return PrimeTagDetailFragment.Companion.newInstance(name, primeKey);
                case "我的插画收藏":
                    return FragmentCollection.newInstance(0);
                case "我的小说收藏":
                    return FragmentCollection.newInstance(1);
                case "追更列表":
                    return FragmentCollection.newInstance(3);
                case "我的关注":
                    return FragmentCollection.newInstance(2);
                case "小说书签":
                    return new NovelMarkersFeedFragment();
                case "主题颜色":
                    // feeds 框架版(替代 legacy FragmentColors);静态目录单页,带 toolbar。
                    // 设置 → 标签译文颜色 也复用本页，通过 extra 切成选择器模式。
                    return ThemeColorFeedFragment.newInstance(
                            intent.getBooleanExtra(
                                    ThemeColorFeedFragment.ARG_SELECT_TAG_TRANSLATION_COLOR,
                                    false));
                case "测试测试":
                    return new FragmentSAF();
                case "举报插画":
                    // flagObjectId 是插画/用户等真实业务 ID，必须走 long——曾经这里用
                    // getIntExtra 读，跟 Illust.id(Long) 类型不匹配，Bundle 类型不符时静默
                    // 返回 0（不抛异常），导致举报功能提交的 illust_id 恒为 0，从未生效过。
                    return FlagReasonFragment.Companion.newInstance(
                            intent.getLongExtra(FlagDescFragment.FlagObjectIdKey, 0L),
                            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0)
                    );
                case "填写举报详细信息":
                    return FlagDescFragment.Companion.newInstance(
                            intent.getIntExtra(FlagDescFragment.FlagTopicIdKey, 0),
                            intent.getStringExtra(FlagDescFragment.FlagTopicTitleKey),
                            intent.getLongExtra(FlagDescFragment.FlagObjectIdKey, 0L),
                            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0)
                    );
                case "相关用户":
                    return RelatedUserFeedFragment.newInstance(Params.getUserId(intent));
                case "Markdown":
                    String url = intent.getStringExtra(Params.URL);
                    return FragmentMarkdown.newInstance(url);
                case "版本历史":
                    return new ceui.lisa.update.FragmentVersionHistory();
                case "发现":
                    // feeds 框架版发现(算法流)，替代 legacy FragmentDiscovery
                    return new ceui.pixiv.ui.discovery.DiscoveryFeedFragment();
                case "当前最热":
                    return new ceui.pixiv.ui.recommend.FragmentRecentRecommend();
                case "站长推荐":
                    return new ceui.pixiv.ui.recommend.FragmentSiteRecommend();
                case "画师榜":
                    return ceui.pixiv.ui.recommend.ArtistRankFeedFragment.newInstance("total");
                case "画师均分榜":
                    return ceui.pixiv.ui.recommend.ArtistRankFeedFragment.newInstance("avg");
                case "浏览量榜":
                    return ceui.pixiv.ui.recommend.ViewRankFeedFragment.newInstance();
                case "pixiv漫画":
                    return new ceui.pixiv.ui.comic.ComicTopFeedFragment();
                case "FANBOX首页":
                    return new ceui.pixiv.ui.fanbox.FanboxHomeFragment();
                case "FANBOX帖子":
                    return ceui.pixiv.ui.fanbox.FanboxPostDetailFragment.newInstance(
                            intent.getStringExtra(
                                    ceui.pixiv.ui.fanbox.FanboxPostDetailFragment.ARG_POST_ID));
                case "收藏榜":
                    return ceui.pixiv.ui.recommend.BookmarkRankFeedFragment.newInstance(null);
                case "AI榜":
                    // 同一个 Fragment,多带一个 ?ai=only(同画师榜/均分榜共用 ArtistRankFeedFragment)
                    return ceui.pixiv.ui.recommend.BookmarkRankFeedFragment.newInstance(
                            ceui.pixiv.ui.recommend.BookmarkRankFeedFragmentKt.AI_ONLY);
                case "年代榜":
                    return ceui.pixiv.ui.recommend.YearRankFragment.newInstance();
                case "标签榜":
                    return ceui.pixiv.ui.recommend.TagRankFragment.newInstance();
                case "壁纸榜":
                    return ceui.pixiv.ui.recommend.WallpaperRankFragment.newInstance();
                case "操作记录":
                    return new ceui.pixiv.ui.recommend.FragmentEventHistory();
                case "批量下载Debug":
                    return new ceui.pixiv.ui.debug.BulkDownloadDebugFragment();
                case "SAF写入压测":
                    return new ceui.pixiv.ui.debug.SafPerfTestFragment();
                case "网络测试":
                    return new ceui.pixiv.ui.debug.NetworkTestFragment();
                case "标签热度导出":
                    return new ceui.pixiv.ui.debug.PopularTagExportFragment();
                case "同义词词典":
                    // 同义词词典管理页（issue #904 按标签收藏优化）
                    return new ceui.pixiv.ui.synonym.SynonymDictFragment();
                case "聊天室": {
                    // peer_uid > 0 → 1v1 with that pixiv user; otherwise →
                    // conversation LIST (global + any 1v1 the user has touched
                    // locally). The list row tap path re-enters this dispatch
                    // with peer_uid set, so the per-room chat fragment opens
                    // exactly the same way it did before.
                    long peerUid = intent.getLongExtra(EXTRA_CHAT_PEER_UID, 0L);
                    return peerUid > 0L
                            ? ceui.pixiv.chat.ui.DemoChatListFragment.Companion.newInstanceForPeer(peerUid)
                            : new ceui.pixiv.chat.ui.ChatRoomListFragment();
                }
                case "聊天-全员公屏": {
                    // Explicit "open the global room" entry. Used by the
                    // conversation list when the user taps the Global row,
                    // since dispatching back through "聊天室" without peer_uid
                    // would route to the list itself.
                    return new ceui.pixiv.chat.ui.DemoChatListFragment();
                }
                case "广场":
                    return new ceui.pixiv.plaza.ui.PlazaFragment();
                case "发帖": {
                    // 从插画 V3「分享至广场」入口进来会带 ILLUST_ID,需透传给 compose fragment
                    // 让它预附这张 illust;广场右上「+」入口不带,走空白编辑器。
                    ceui.pixiv.plaza.ui.PlazaComposeFragment composeFragment =
                        new ceui.pixiv.plaza.ui.PlazaComposeFragment();
                    long prefillIllustId = intent.getLongExtra(
                        ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID, 0L);
                    if (prefillIllustId > 0L) {
                        android.os.Bundle args = new android.os.Bundle();
                        args.putLong(
                            ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID,
                            prefillIllustId);
                        composeFragment.setArguments(args);
                    }
                    return composeFragment;
                }
                case "Plaza打开作品": {
                    // 从广场卡片点 illust 缩略走这条;只带 ILLUST_ID,
                    // ArtworkV3ViewModel 自己会按 id lazy load。
                    int illustId = intent.getIntExtra(ceui.lisa.utils.Params.ILLUST_ID, 0);
                    return ceui.pixiv.ui.detail.ArtworkV3Fragment.newInstance(illustId);
                }
                case "Plaza帖子详情": {
                    long postId = intent.getLongExtra(
                        ceui.pixiv.plaza.ui.PlazaPostDetailFragment.EXTRA_POST_ID, 0L);
                    return ceui.pixiv.plaza.ui.PlazaPostDetailFragment.Companion.newInstance(postId);
                }
                case "通知中心":
                    return new NotificationPagerFragment();
                case "通知展开":
                    return new NotificationViewMoreFragment();
                case "公告分类":
                    return new InfoCategoryListFragment();
                case "离线快照":
                    return new ceui.pixiv.snapshot.SnapshotManagerFragment();
                case "快照查看": {
                    String snapshotId = intent.getStringExtra(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID);
                    boolean snapshotIsAuto = intent.getBooleanExtra(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false);
                    return ceui.pixiv.ui.detail.ArtworkV3Fragment.Companion.newInstanceSnapshot(
                            snapshotId, snapshotIsAuto);
                }
                case "快照经典查看": {
                    ceui.lisa.fragments.FragmentIllust classic =
                            new ceui.lisa.fragments.FragmentIllust();
                    android.os.Bundle classicSnapshotArgs = new android.os.Bundle();
                    classicSnapshotArgs.putInt("illust_id", 0);
                    classicSnapshotArgs.putString(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID,
                            intent.getStringExtra(
                                    ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID));
                    classicSnapshotArgs.putBoolean(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO,
                            intent.getBooleanExtra(
                                    ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false));
                    classic.setArguments(classicSnapshotArgs);
                    return classic;
                }
                case "快照评论": {
                    ceui.pixiv.ui.comments.CommentsFragment comments =
                            new ceui.pixiv.ui.comments.CommentsFragment();
                    android.os.Bundle snapshotCommentArgs = new android.os.Bundle();
                    snapshotCommentArgs.putLong("objectId",
                            intent.getLongExtra("objectId", 0L));
                    snapshotCommentArgs.putLong("objectArthurId",
                            intent.getLongExtra("objectArthurId", 0L));
                    snapshotCommentArgs.putString("objectType",
                            intent.getStringExtra("objectType"));
                    snapshotCommentArgs.putString(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID,
                            intent.getStringExtra(
                                    ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_ID));
                    snapshotCommentArgs.putBoolean(
                            ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO,
                            intent.getBooleanExtra(
                                    ceui.pixiv.snapshot.SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false));
                    comments.setArguments(snapshotCommentArgs);
                    return comments;
                }
                default:
                    return new Fragment();
            }
        }
        return null;
    }


    private CommentsFragment getCommentsFragment(Intent intent) {
        int workId = intent.getIntExtra(Params.ILLUST_ID, 0);

        if (workId == 0) {
            workId = intent.getIntExtra(Params.NOVEL_ID, 0);
            Novel hit = ObjectPool.INSTANCE.getNovel(workId).getValue();
            long illustArthurId = getArthurIdFromNovel(hit);
            return CommentsFragment.Companion.newInstance(workId, illustArthurId, ObjectType.NOVEL);
        } else {
            Illust hit = ObjectPool.INSTANCE.getIllust(workId).getValue();
            long illustArthurId = getArthurIdFromIllust(hit);
            return CommentsFragment.Companion.newInstance(workId, illustArthurId, ObjectType.ILLUST);
        }
    }

    // Helper method to extract Arthur ID from Novel
    private long getArthurIdFromNovel(Novel hit) {
        if (hit != null) {
            User user = hit.getUser();
            if (user != null) {
                return user.getId();
            }
        }
        return 0;
    }

    // Helper method to extract Arthur ID from Illust
    private long getArthurIdFromIllust(Illust hit) {
        if (hit != null) {
            ceui.loxia.User user = hit.getUser();
            if (user != null) {
                return user.getId();
            }
        }
        return 0;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 音量键翻页：在 dispatchKeyEvent 这一最早入口拦截，避免 Android 16 / HyperOS 3+ 的
        // 多应用音量面板在事件抵达 onKeyDown 之前就被 SystemUI 弹出 (issue #874)。
        // 同时配对消费 ACTION_UP，防止部分 ROM 在 UP 时再次触发系统音量 UI。
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            boolean handled = false;
            if (childFragment instanceof NovelReaderV3Fragment) {
                handled = ((NovelReaderV3Fragment) childFragment).handleVolumeKey(keyCode);
            } else if (childFragment instanceof ComicReaderV3Fragment) {
                handled = ((ComicReaderV3Fragment) childFragment).handleVolumeKey(keyCode);
            }
            if (handled) {
                volumeKeysAwaitingUp.add(keyCode);
                return true;
            }
        } else if (action == KeyEvent.ACTION_UP && volumeKeysAwaitingUp.remove(keyCode)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_fragment;
    }

    @Override
    protected void initView() {
        // 返回键/返回手势:这里故意不挂任何 OnBackPressedCallback。
        //
        // 预测式返回(targetSdk 35+ 默认开启)的跨 Activity / 回桌面动画只在「app 没向系统
        // 注册任何返回回调」时才会播:只要 OnBackPressedDispatcher 里有一个 enabled 的
        // callback,AndroidX 就会向 WindowOnBackInvokedDispatcher 注册 OnBackInvokedCallback,
        // 系统随即放弃自己的动画,手势落下后只是干巴巴地回调 → 以前这里那个常开的兜底
        // callback 把全 app 几乎所有页面的预测式返回都掐死了。
        //
        // 没有 callback 时系统走 Activity 默认返回(finishAfterTransition),动画由系统负责。
        // 需要拦返回的页面(网页历史后退、阅读器收顶底栏、未保存确认…)各自在 Fragment 里
        // 注册 callback,并且只在「真的有东西可拦」时才 setEnabled(true) —— 必须提前维护好
        // enabled,系统在手势开始那一刻就决定播不播动画,handleOnBackPressed 里再判断已经晚了。
        // 子 Fragment 返回栈由 FragmentManager 自带的 callback 处理(本 app 没有 addToBackStack)。
    }

    @Override
    protected void initData() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment == null) {
            fragment = createNewFragment();
            if (fragment != null) {
                fragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment)
                        .commit();
                childFragment = fragment;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (childFragment != null) {
            childFragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean hideStatusBar() {
        if ("相关评论".equals(dataType)) {
            return false;
        } else {
            return getIntent().getBooleanExtra("hideStatusBar", true);
        }
    }

    @Override
    public void onColorSelected(int dialogId, int color) {
        // 旧版小说阅读器 FragmentNovelHolder 已删除（改用 NovelReaderV3 的设置面板），
        // 这里的颜色回调不再有承接对象，保留空实现满足接口。
    }

    @Override
    public void onDialogDismissed(int dialogId) {

    }

    public void onFontSizeSelected(int size) {
        // 同上：旧阅读器字号回调已随 FragmentNovelHolder 一并废弃。
    }
}
