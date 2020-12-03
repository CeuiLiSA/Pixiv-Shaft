package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.blankj.utilcode.util.BarUtils;
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.lisa.fragments.FragmentAboutApp;
import ceui.lisa.fragments.FragmentBookedTag;
import ceui.lisa.fragments.FragmentCollection;
import ceui.lisa.fragments.FragmentColors;
import ceui.lisa.fragments.FragmentComment;
import ceui.lisa.fragments.FragmentDoing;
import ceui.lisa.fragments.FragmentDonate;
import ceui.lisa.fragments.FragmentDownload;
import ceui.lisa.fragments.FragmentEditAccount;
import ceui.lisa.fragments.FragmentEditFile;
import ceui.lisa.fragments.FragmentFeature;
import ceui.lisa.fragments.FragmentFileName;
import ceui.lisa.fragments.FragmentFollowUser;
import ceui.lisa.fragments.FragmentHistory;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentLikeNovel;
import ceui.lisa.fragments.FragmentListSimpleUser;
import ceui.lisa.fragments.FragmentLive;
import ceui.lisa.fragments.FragmentLocalUsers;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.fragments.FragmentMangaSeries;
import ceui.lisa.fragments.FragmentMangaSeriesDetail;
import ceui.lisa.fragments.FragmentMultiDownld;
import ceui.lisa.fragments.FragmentMutedTags;
import ceui.lisa.fragments.FragmentNew;
import ceui.lisa.fragments.FragmentNewNovel;
import ceui.lisa.fragments.FragmentNewNovels;
import ceui.lisa.fragments.FragmentNiceFriend;
import ceui.lisa.fragments.FragmentNovelHolder;
import ceui.lisa.fragments.FragmentNovelSeries;
import ceui.lisa.fragments.FragmentNovelSeriesDetail;
import ceui.lisa.fragments.FragmentPopularNovel;
import ceui.lisa.fragments.FragmentPv;
import ceui.lisa.fragments.FragmentRecmdIllust;
import ceui.lisa.fragments.FragmentRecmdUser;
import ceui.lisa.fragments.FragmentRelatedIllust;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.fragments.FragmentSearch;
import ceui.lisa.fragments.FragmentSearchUser;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentStorage;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.fragments.FragmentUserInfo;
import ceui.lisa.fragments.FragmentUserManga;
import ceui.lisa.fragments.FragmentUserNovel;
import ceui.lisa.fragments.FragmentWalkThrough;
import ceui.lisa.fragments.FragmentWebView;
import ceui.lisa.fragments.FragmentWhoFollowThisUser;
import ceui.lisa.fragments.FragmentWorkSpace;
import ceui.lisa.fragments.TestFragment;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseResult;

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
                    return new FragmentHistory();
                case "网页链接": {
                    String url = intent.getStringExtra(Params.URL);
                    String title = intent.getStringExtra(Params.TITLE);
                    return FragmentWebView.newInstance(title, url);
                }
                case "设置":
                    return new FragmentSettings();
                case "推荐用户":
                    return new FragmentRecmdUser();
                case "特辑":
                    return new FragmentPv();
                case "搜索用户": {
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentSearchUser.newInstance(keyword);
                }
                case "以图搜图":
                    ReverseResult result = intent.getParcelableExtra("result");
                    return FragmentWebView.newInstance(result.getTitle(), result.getUrl(), result.getResponseBody(), result.getMime(), result.getEncoding(), result.getHistory_url());
                case "相关评论": {
                    BarUtils.setStatusBarColor(mActivity, android.R.attr.colorPrimary);
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(Params.ILLUST_TITLE);
                    return FragmentComment.newInstance(id, title);
                }
                case "账号管理":
                    return new FragmentLocalUsers();
                case "按标签筛选": {
                    return FragmentBookedTag.newInstance(intent.getStringExtra(EXTRA_KEYWORD));
                }
                case "按标签收藏": {
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    return FragmentSB.newInstance(id);
                }
                case "关于软件":
                    return new FragmentAboutApp();
                case "批量下载":
                    return new FragmentMultiDownld();
                case "画廊":
                    return new FragmentWalkThrough();
                case "正在关注":
                    return FragmentFollowUser.newInstance(
                            getIntent().getIntExtra(Params.USER_ID, 0),
                            Params.TYPE_PUBLUC, true);
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
                            true);
                case "漫画作品":
                    return FragmentUserManga.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "插画/漫画收藏":
                    return FragmentLikeIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            Params.TYPE_PUBLUC, true);
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
                            Params.TYPE_PUBLUC, true);
                case "小说作品":
                    return FragmentUserNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "小说详情":
                    return FragmentNovelHolder.newInstance((NovelBean) intent.getSerializableExtra(Params.CONTENT));
                case "图片详情":
                    return FragmentImageDetail.newInstance(intent.getStringExtra(Params.URL));
                case "绑定邮箱":
                    return new FragmentEditAccount();
                case "编辑个人资料":
                    return new FragmentEditFile();
                case "热门直播":
                    return new FragmentLive();
                case "标签屏蔽记录":
                    return new FragmentMutedTags();
                case "修改命名方式":
                    return FragmentFileName.newInstance();
                case "捐赠":
                    return FragmentDonate.newInstance();
                case "关注者的小说":
                    return new FragmentNewNovels();
                case "漫画系列作品":
                    return FragmentMangaSeries.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "漫画系列详情":
                    return FragmentMangaSeriesDetail.newInstance(intent.getIntExtra(Params.ID, 0));
                case "小说系列作品":
                    return new FragmentNovelSeries();
                case "精华列":
                    return new FragmentFeature();
                case "我的作业环境":
                    return new FragmentWorkSpace();
                case "存储访问":
                    return new FragmentStorage();
                case "任务中心":
                    return new FragmentDoing();
                case "我的插画收藏":
                    return FragmentCollection.newInstance(0);
                case "我的小说收藏":
                    return FragmentCollection.newInstance(1);
                case "我的关注":
                    return FragmentCollection.newInstance(2);
                case "主题颜色":
                    return new FragmentColors();
                case "测试测试":
                    return new TestFragment();
                default:
                    return new Fragment();
            }
        }
        return null;
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
            ((FragmentNovelHolder) childFragment).setColor(color);
            Shaft.sSettings.setNovelHolderColor(color);
            Local.setSettings(Shaft.sSettings);
        }
    }

    @Override
    public void onDialogDismissed(int dialogId) {

    }
}
