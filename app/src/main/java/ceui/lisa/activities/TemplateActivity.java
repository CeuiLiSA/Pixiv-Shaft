package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.HashSet;
import java.util.Set;

import com.jaredrummler.android.colorpicker.ColorPickerDialogListener;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.lisa.fragments.FragmentAboutApp;
import ceui.lisa.fragments.FragmentBookedTag;
import ceui.lisa.fragments.FragmentCollection;
import ceui.lisa.fragments.FragmentColors;
import ceui.lisa.fragments.FragmentDiscovery;
import ceui.lisa.fragments.FragmentDoing;
import ceui.lisa.fragments.FragmentDonate;
import ceui.lisa.fragments.FragmentEditAccount;
import ceui.lisa.fragments.FragmentEditFile;
import ceui.lisa.fragments.FragmentFeature;
import ceui.lisa.fragments.FragmentFileName;
import ceui.lisa.fragments.FragmentFollowUser;
import ceui.lisa.fragments.FragmentHistory;
import ceui.lisa.fragments.FragmentHistoryTabs;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentLikeNovel;
import ceui.lisa.fragments.FragmentListSimpleUser;
import ceui.lisa.fragments.FragmentLive;
import ceui.lisa.fragments.FragmentLocalUsers;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.fragments.FragmentMangaSeries;
import ceui.lisa.fragments.FragmentMangaSeriesDetail;
import ceui.lisa.fragments.FragmentMarkdown;
import ceui.lisa.fragments.FragmentNew;
import ceui.lisa.fragments.FragmentNewNovel;
import ceui.lisa.fragments.FragmentNewNovels;
import ceui.lisa.fragments.FragmentNiceFriend;
import ceui.lisa.fragments.FragmentNovelHolder;
import ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment;
import ceui.pixiv.ui.comic.reader.ComicReaderV3Fragment;
import ceui.pixiv.ui.novel.NovelSeriesFragment;
import ceui.pixiv.ui.novel.NovelTextFragment;
import ceui.pixiv.ui.novel.UncategorizedNovelsFragment;
import ceui.lisa.fragments.FragmentNovelMarkers;
import ceui.lisa.fragments.FragmentNovelSeries;
import ceui.lisa.fragments.FragmentPopularNovel;
import ceui.lisa.fragments.FragmentPv;
import ceui.lisa.fragments.FragmentRecmdIllust;
import ceui.lisa.fragments.FragmentRecmdUser;
import ceui.lisa.fragments.FragmentRelatedIllust;
import ceui.lisa.fragments.FragmentRelatedUser;
import ceui.lisa.fragments.FragmentSAF;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.fragments.FragmentSearch;
import ceui.lisa.fragments.FragmentSearchUser;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentStorage;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.fragments.FragmentUserInfo;
import ceui.lisa.fragments.FragmentUserManga;
import ceui.lisa.fragments.FragmentUserNovel;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.lisa.fragments.FragmentWalkThrough;
import ceui.lisa.fragments.FragmentWebView;
import ceui.lisa.fragments.FragmentWhoFollowThisUser;
import ceui.lisa.fragments.FragmentWorkSpace;
import ceui.lisa.fragments.RecmdUserMap;
import ceui.lisa.fragments.RecmdUserSnapshot;
import ceui.lisa.helper.BackHandlerHelper;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseResult;
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
                    return FragmentRelatedIllust.newInstance(id, title);
                }
                case "浏览记录":
                    return new FragmentHistoryTabs();
                case "网页链接": {
                    String url = intent.getStringExtra(Params.URL);
                    String title = intent.getStringExtra(Params.TITLE);
                    boolean preferPreserve = intent.getBooleanExtra(Params.PREFER_PRESERVE, false);
                    return FragmentWebView.newInstance(title, url, preferPreserve);
                }
                case "设置":
                    return new FragmentSettings();
                case "推荐用户": {
                    // Paired with FragmentRight#seeMore: the producer stashes
                    // the Feed horizontal preview's snapshot under a random
                    // key in RecmdUserMap and passes the key here. We remove
                    // it on consume so the map doesn't leak.
                    String recmdKey = intent.getStringExtra(Params.USER_MODEL);
                    if (recmdKey == null) {
                        return new FragmentRecmdUser();
                    }
                    RecmdUserSnapshot snapshot = RecmdUserMap.store.remove(recmdKey);
                    if (snapshot == null) {
                        return new FragmentRecmdUser();
                    }
                    return new FragmentRecmdUser(snapshot.items, snapshot.nextUrl);
                }
                case "特辑":
                    return new FragmentPv();
                case "搜索用户": {
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentSearchUser.newInstance(keyword);
                }
                case "以图搜图":
                    ReverseResult result = intent.getParcelableExtra(Params.REVERSE_SEARCH_RESULT);
                    Uri imageUri = intent.getParcelableExtra(Params.REVERSE_SEARCH_IMAGE_URI);
                    return FragmentWebView.newInstance(result.getTitle(), result.getUrl(), result.getResponseBody(), result.getMime(), result.getEncoding(), result.getHistory_url(), imageUri);
                case "相关评论": {
                    return getCommentsFragment(intent);
                }
                case "账号管理":
                    return new FragmentLocalUsers();
                case "按标签筛选": {
                    return FragmentBookedTag.newInstance(intent.getIntExtra(Params.DATA_TYPE, 0), intent.getStringExtra(EXTRA_KEYWORD));
                }
                case "按标签收藏": {
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String type = intent.getStringExtra(Params.DATA_TYPE);
                    String[] tagNames = intent.getStringArrayExtra(Params.TAG_NAMES);
                    return FragmentSB.newInstance(id, type, tagNames);
                }
                case "关于软件":
                    return new FragmentAboutApp();
                case "批量下载队列":
                    // 统一路由到新的 V3 下载管理页（默认进队列 tab）
                    return new ceui.pixiv.ui.download.DownloadManagerV3Fragment();
                case "批量选择":
                    return new ceui.pixiv.ui.bulk.BulkSelectV3Fragment();
                case "画廊":
                    return new FragmentWalkThrough();
                case "正在关注":
                    return FragmentFollowUser.newInstance(
                            getIntent().getIntExtra(Params.USER_ID, 0),
                            Params.TYPE_PUBLIC, true);
                case "好P友":
                    return new FragmentNiceFriend();
                case "搜索":
                    return new FragmentSearch();
                case "详细信息":
                    return new FragmentUserInfo();
                case "最新作品":
                    return new FragmentNew();
                case "粉丝":
                    return FragmentWhoFollowThisUser.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "喜欢这个作品的用户":
                    return FragmentListSimpleUser.newInstance((IllustsBean) intent.getSerializableExtra(Params.CONTENT));
                case "小说系列详情": {
                    // Legacy 路由——桥接到新页 NovelSeriesFragment。保留 case
                    // 兼容外部深链或仍在路上的字符串拼接调用；内部入口都已迁到
                    // "小说系列" + ARG_SERIES_ID(Long)。
                    long sid = intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID,
                            intent.getIntExtra(Params.ID, 0));
                    return NovelSeriesFragment.Companion.newInstance(sid);
                }
                case "插画作品":
                    return FragmentUserIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true, intent.getIntExtra(Params.INITIAL_OFFSET, 0),
                            intent.getStringExtra(Params.TARGET_DATE));
                case "漫画作品":
                    return FragmentUserManga.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true, intent.getIntExtra(Params.INITIAL_OFFSET, 0),
                            intent.getStringExtra(Params.TARGET_DATE));
                case "插画/漫画收藏":
                    return FragmentLikeIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            Params.TYPE_PUBLIC, true);
                case "下载管理":
                    return new ceui.pixiv.ui.download.DownloadManagerV3Fragment();
                case "推荐漫画":
                    return FragmentRecmdIllust.newInstance("漫画");
                case "热度小说":
                    return FragmentPopularNovel.newInstance(intent.getStringExtra(Params.KEY_WORD));
                case "推荐小说":
                    return new FragmentNewNovel();
                case "小说收藏":
                    return FragmentLikeNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            Params.TYPE_PUBLIC, true);
                case "小说作品":
                    return FragmentUserNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "小说详情": {
                    NovelBean bean = (NovelBean) intent.getSerializableExtra(Params.CONTENT);
                    long tid = bean != null ? bean.getId() : intent.getLongExtra(Params.NOVEL_ID, 0L);
                    return NovelTextFragment.Companion.newInstance(tid);
                }
                case "小说正文": {
                    NovelBean bean = (NovelBean) intent.getSerializableExtra(Params.CONTENT);
                    if (bean != null) {
                        return NovelReaderV3Fragment.newInstance(bean);
                    }
                    long rid = intent.getLongExtra(Params.NOVEL_ID, 0L);
                    return NovelReaderV3Fragment.newInstance(rid);
                }
                case "漫画阅读": {
                    long iid = intent.getLongExtra(Params.ILLUST_ID, 0L);
                    if (iid == 0L) iid = intent.getIntExtra(Params.ILLUST_ID, 0);
                    return ComicReaderV3Fragment.newInstance(iid);
                }
                case "小说系列": {
                    long sid = intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID, 0L);
                    return NovelSeriesFragment.Companion.newInstance(sid);
                }
                case "未归类小说": {
                    int uid = intent.getIntExtra(Params.USER_ID, 0);
                    return UncategorizedNovelsFragment.Companion.newInstance((long) uid);
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
                case "漫画OCR模型下载":
                    return ceui.pixiv.ui.translate.MangaOcrDownloadFragment.newInstance(
                            intent.getStringExtra("manga_ocr_model_name"));
                case "漫画文本框检测模型下载":
                    return ceui.pixiv.ui.translate.ComicTextDetectorDownloadFragment.newInstance(
                            intent.getStringExtra("ctd_model_name"));
                case "NLLB翻译模型下载":
                    return ceui.pixiv.ui.translate.NllbDownloadFragment.newInstance(
                            intent.getStringExtra("nllb_model_name"));
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
                case "热门直播":
                    return new FragmentLive();
                case "标签屏蔽记录":
                    return FragmentViewPager.newInstance(Params.VIEW_PAGER_MUTED);
                case "修改命名方式":
                    return FragmentFileName.newInstance();
                case "下载路径与文件名":
                    return new ceui.pixiv.ui.settings.DownloadPathSettingsFragment();
                case "小说信息头":
                    return new ceui.pixiv.ui.settings.NovelHeaderSettingsFragment();
                case "捐赠":
                    return FragmentDonate.newInstance();
                case "关注者的小说":
                    return new FragmentNewNovels();
                case "漫画系列作品":
                    return FragmentMangaSeries.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "漫画系列详情":
                    return FragmentMangaSeriesDetail.newInstance(intent.getIntExtra(Params.MANGA_SERIES_ID, 0));
                case "小说系列作品":
                    return new FragmentNovelSeries();
                case "精华列":
                    return new FragmentFeature();
                case "我的作业环境":
                    return new FragmentWorkSpace();
                case "PrimeTagsList":
                    return new PrimeTagsFragment();
                case "PrimeTagDetail":
                    String path = intent.getStringExtra("path");
                    assert path != null;

                    String name = intent.getStringExtra("name");
                    assert name != null;

                    return PrimeTagDetailFragment.Companion.newInstance(name, path);
                case "存储访问":
                    return new FragmentStorage();
                case "任务中心":
                    return new FragmentDoing();
                case "我的插画收藏":
                    return FragmentCollection.newInstance(0);
                case "我的小说收藏":
                    return FragmentCollection.newInstance(1);
                case "追更列表":
                    return FragmentCollection.newInstance(3);
                case "我的关注":
                    return FragmentCollection.newInstance(2);
                case "小说书签":
                    return new FragmentNovelMarkers();
                case "主题颜色":
                    return new FragmentColors();
                case "测试测试":
                    return new FragmentSAF();
                case "举报插画":
                    return FlagReasonFragment.Companion.newInstance(
                            intent.getIntExtra(FlagDescFragment.FlagObjectIdKey, 0),
                            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0)
                    );
                case "填写举报详细信息":
                    return FlagDescFragment.Companion.newInstance(
                            intent.getIntExtra(FlagDescFragment.FlagReasonIdKey, 0),
                            intent.getIntExtra(FlagDescFragment.FlagObjectIdKey, 0),
                            intent.getIntExtra(FlagDescFragment.FlagObjectTypeKey, 0)
                    );
                case "相关用户":
                    return FragmentRelatedUser.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "Markdown":
                    String url = intent.getStringExtra(Params.URL);
                    return FragmentMarkdown.newInstance(url);
                case "版本历史":
                    return new ceui.lisa.update.FragmentVersionHistory();
                case "发现":
                    return FragmentDiscovery.newInstance();
                case "当前最热":
                    return new ceui.pixiv.ui.recommend.FragmentRecentRecommend();
                case "站长推荐":
                    return new ceui.pixiv.ui.recommend.FragmentSiteRecommend();
                case "操作记录":
                    return new ceui.pixiv.ui.recommend.FragmentEventHistory();
                case "批量下载Debug":
                    return new ceui.pixiv.ui.debug.BulkDownloadDebugFragment();
                case "SAF写入压测":
                    return new ceui.pixiv.ui.debug.SafPerfTestFragment();
                case "网络测试":
                    return new ceui.pixiv.ui.debug.NetworkTestFragment();
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
            NovelBean hit = ObjectPool.INSTANCE.getNovel(workId).getValue();
            int illustArthurId = getArthurIdFromNovel(hit);
            return CommentsFragment.Companion.newInstance(workId, illustArthurId, ObjectType.NOVEL);
        } else {
            IllustsBean hit = ObjectPool.INSTANCE.getIllust(workId).getValue();
            int illustArthurId = getArthurIdFromIllust(hit);
            return CommentsFragment.Companion.newInstance(workId, illustArthurId, ObjectType.ILLUST);
        }
    }

    // Helper method to extract Arthur ID from NovelBean
    private int getArthurIdFromNovel(NovelBean hit) {
        if (hit != null) {
            UserBean user = hit.getUser();
            if (user != null) {
                return user.getId();
            }
        }
        return 0;
    }

    // Helper method to extract Arthur ID from IllustsBean
    private int getArthurIdFromIllust(IllustsBean hit) {
        if (hit != null) {
            UserBean user = hit.getUser();
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (childFragment instanceof FragmentWebView) {
            return ((FragmentWebView) childFragment).getAgentWeb().handleKeyEvent(keyCode, event) ||
                    super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_fragment;
    }

    @Override
    protected void initView() {

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
        if (childFragment instanceof FragmentNovelHolder) {
            if (dialogId == Params.DIALOG_NOVEL_BG_COLOR) {
                Shaft.sSettings.setNovelHolderColor(color);
                ((FragmentNovelHolder) childFragment).setBackgroundColor(color);
            } else if (dialogId == Params.DIALOG_NOVEL_TEXT_COLOR) {
                Shaft.sSettings.setNovelHolderTextColor(color);
                ((FragmentNovelHolder) childFragment).setTextColor(color);
            }

            Local.setSettings(Shaft.sSettings);
        }
    }

    @Override
    public void onDialogDismissed(int dialogId) {

    }

    @Override
    public void onBackPressed() {
        if (!BackHandlerHelper.handleBackPress(this)) {
            super.onBackPressed();
        }
    }

    public void onFontSizeSelected(int size) {
        if (childFragment instanceof FragmentNovelHolder) {
            Shaft.sSettings.setNovelHolderTextSize(size);
            ((FragmentNovelHolder) childFragment).setTextSize(size);
            Local.setSettings(Shaft.sSettings);
        }
    }
}
