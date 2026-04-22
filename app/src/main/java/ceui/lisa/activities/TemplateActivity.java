package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

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
import ceui.lisa.fragments.FragmentDownload;
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
import ceui.lisa.fragments.FragmentMultiDownload;
import ceui.lisa.fragments.FragmentNew;
import ceui.lisa.fragments.FragmentNewNovel;
import ceui.lisa.fragments.FragmentNewNovels;
import ceui.lisa.fragments.FragmentNiceFriend;
import ceui.lisa.fragments.FragmentNovelHolder;
import ceui.pixiv.ui.novel.reader.NovelReaderV3Fragment;
import ceui.pixiv.ui.novel.NovelSeriesFragment;
import ceui.pixiv.ui.novel.NovelTextFragment;
import ceui.pixiv.ui.novel.UncategorizedNovelsFragment;
import ceui.lisa.fragments.FragmentNovelMarkers;
import ceui.lisa.fragments.FragmentNovelSeries;
import ceui.lisa.fragments.FragmentNovelSeriesDetail;
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
import ceui.pixiv.ui.prime.PrimeTagDetailFragment;
import ceui.pixiv.ui.prime.PrimeTagsFragment;

public class TemplateActivity extends BaseActivity<ActivityFragmentBinding> implements ColorPickerDialogListener {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    protected Fragment childFragment;
    private String dataType;

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
                case "批量下载":
                    return new FragmentMultiDownload();
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
                case "小说系列详情":
                    return FragmentNovelSeriesDetail.newInstance(intent.getIntExtra(Params.ID, 0));
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
                    return new FragmentDownload();
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
                case "小说系列": {
                    long sid = intent.getLongExtra(NovelSeriesFragment.ARG_SERIES_ID, 0L);
                    return NovelSeriesFragment.Companion.newInstance(sid);
                }
                case "未归类小说": {
                    int uid = intent.getIntExtra(Params.USER_ID, 0);
                    return UncategorizedNovelsFragment.Companion.newInstance((long) uid);
                }
                case "图片详情":
                    return FragmentImageDetail.newInstance(intent.getStringExtra(Params.URL), intent.getStringExtra(Params.TITLE));
                case "画质增强对比":
                    return ceui.pixiv.ui.upscale.UpscaleCompareFragment.newInstance(
                            intent.getStringExtra("upscaled_path"),
                            intent.getStringExtra("original_path"));
                case "AI画质提升":
                    return new ceui.pixiv.ui.upscale.FragmentAiUpscale();
                case "OCR结果":
                    return ceui.pixiv.ui.upscale.OcrResultFragment.newInstance(
                            intent.getStringArrayListExtra("ocr_texts"));
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
                case "漫画翻译":
                    return ceui.pixiv.ui.translate.MangaTranslationFragment.newInstance(
                            intent.getStringExtra("translated_path"),
                            intent.getStringExtra("original_path"));
                case "漫画OCR模型下载":
                    return ceui.pixiv.ui.translate.MangaOcrDownloadFragment.newInstance(
                            intent.getStringExtra("manga_ocr_model_name"));
                case "NLLB翻译模型下载":
                    return ceui.pixiv.ui.translate.NllbDownloadFragment.newInstance(
                            intent.getStringExtra("nllb_model_name"));
                case "Sakura翻译模型下载":
                    return ceui.pixiv.ui.translate.SakuraDownloadFragment.newInstance(
                            intent.getStringExtra("sakura_model_name"));
                case "Sakura翻译":
                    return new ceui.pixiv.ui.translate.SakuraTranslateDemoFragment();
                case "绑定邮箱":
                    return new FragmentEditAccount();
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
