package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.lisa.fragments.FragmentAboutApp;
import ceui.lisa.fragments.FragmentListSimpleUser;
import ceui.lisa.fragments.FragmentMultiDownld;
import ceui.lisa.fragments.FragmentNovelSeries;
import ceui.lisa.fragments.FragmentRecmdIllust;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.fragments.FragmentUserInfo;
import ceui.lisa.fragments.FragmentBookedTag;
import ceui.lisa.fragments.FragmentComment;
import ceui.lisa.fragments.FragmentCollection;
import ceui.lisa.fragments.FragmentDownload;
import ceui.lisa.fragments.FragmentEditAccount;
import ceui.lisa.fragments.FragmentEditFile;
import ceui.lisa.fragments.FragmentFollowUser;
import ceui.lisa.fragments.FragmentHitokoto;
import ceui.lisa.fragments.FragmentHistory;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentLikeNovel;
import ceui.lisa.fragments.FragmentLive;
import ceui.lisa.fragments.FragmentLocalUsers;
import ceui.lisa.fragments.FragmentMutedTags;
import ceui.lisa.fragments.FragmentNew;
import ceui.lisa.fragments.FragmentNiceFriend;
import ceui.lisa.fragments.FragmentNovelHolder;
import ceui.lisa.fragments.FragmentPv;
import ceui.lisa.fragments.FragmentRecmdNovel;
import ceui.lisa.fragments.FragmentRecmdUser;
import ceui.lisa.fragments.FragmentRelatedIllust;
import ceui.lisa.fragments.FragmentSearch;
import ceui.lisa.fragments.FragmentSearchResult;
import ceui.lisa.fragments.FragmentSearchUser;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.fragments.FragmentUserManga;
import ceui.lisa.fragments.FragmentUserNovel;
import ceui.lisa.fragments.FragmentWalkThrough;
import ceui.lisa.fragments.FragmentWebView;
import ceui.lisa.fragments.FragmentWhoFollowThisUser;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseResult;

public class TemplateActivity extends BaseActivity<ActivityFragmentBinding> {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    protected Fragment childFragment;

    private boolean needFixTop = false;
    private boolean needDisableFullscreenLayout = false;

    @Override
    public boolean fixTop() {
        return needFixTop;
    }

    @Override
    public boolean disableFullscreenLayout() {
        return needDisableFullscreenLayout;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            needFixTop = savedInstanceState.getBoolean("needFixTop");
        }
        super.onCreate(savedInstanceState);
    }

    protected Fragment createNewFragment() {
        Intent intent = getIntent();
        String dataType = intent.getStringExtra(EXTRA_FRAGMENT);

        if (dataType != null) {
            //在顶部进入状态栏时使用 needFixTop = true 来修复
            switch (dataType) {
                case "登录注册":
                    needFixTop = false;
                    BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.colorPrimary));
                    return new FragmentLogin();
                case "搜索结果": {
                    needFixTop = false;
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentSearchResult.newInstance(keyword);
                }
                case "相关作品": {
                    needFixTop = false;
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(Params.ILLUST_TITLE);
                    return FragmentRelatedIllust.newInstance(id, title);
                }
                case "浏览记录":
                    needFixTop = false;
                    return new FragmentHistory();
                case "网页链接": {
                    needFixTop = false;
                    String url = intent.getStringExtra(Params.URL);
                    String title = intent.getStringExtra(Params.TITLE);
                    return FragmentWebView.newInstance(title, url);
                }
                case "设置":
                    needFixTop = false;
                    return FragmentSettings.newInstance();
                case "推荐用户":
                    return new FragmentRecmdUser();
                case "特辑":
                    needFixTop = false;
                    return new FragmentPv();
                case "搜索用户": {
                    needFixTop = false;
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentSearchUser.newInstance(keyword);
                }
                case "以图搜图":
                    needFixTop = false;
                    ReverseResult result = intent.getParcelableExtra("result");
                    return FragmentWebView.newInstance(result.getTitle(), result.getUrl(), result.getResponseBody(), result.getMime(), result.getEncoding(), result.getHistory_url());
                case "相关评论": {
                    needDisableFullscreenLayout = true;
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(Params.ILLUST_TITLE);
                    return FragmentComment.newInstance(id, title);
                }
                case "账号管理":
                    needFixTop = false;
                    return new FragmentLocalUsers();
                case "按标签筛选": {
                    needFixTop = true;
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentBookedTag.newInstance(keyword);
                }
                case "按标签收藏": {
                    needFixTop = false;
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    return FragmentSB.newInstance(id);
                }
                case "关于软件":
                    needFixTop = false;
                    return new FragmentAboutApp();
                case "批量下载":
                    needDisableFullscreenLayout = true;
                    return new FragmentMultiDownld();
                case "画廊":
                    needFixTop = false;
                    return new FragmentWalkThrough();
//                case "License":
//                    return new FragmentLicense();
                case "正在关注":
                    needFixTop = false;
                    return FragmentFollowUser.newInstance(
                            getIntent().getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "好P友":
                    needFixTop = false;
                    return new FragmentNiceFriend();
                case "搜索":
                    needFixTop = false;
                    return new FragmentSearch();
                case "详细信息":
                    needFixTop = false;
                    return new FragmentUserInfo();
                case "一言":
                    needFixTop = false;
                    if (Dev.isDev) {
                        //return new FragmentTest();
                    } else {
                        return new FragmentHitokoto();
                    }
                case "最新作品":
                    needFixTop = true;
                    return new FragmentNew();
                case "粉丝":
                    needFixTop = true;
                    return FragmentWhoFollowThisUser.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "喜欢这个作品的用户":
                    return FragmentListSimpleUser.newInstance((IllustsBean) intent.getSerializableExtra(Params.CONTENT));
                case "小说系列作品":
                    return FragmentNovelSeries.newInstance((NovelBean) intent.getSerializableExtra(Params.CONTENT));
                case "插画作品":
                    needFixTop = false;
                    return FragmentUserIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "漫画作品":
                    needFixTop = false;
                    return FragmentUserManga.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "插画/漫画收藏":
                    needFixTop = false;
                    return FragmentLikeIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "下载管理":
                    needFixTop = true;
                    return new FragmentDownload();
                case "收藏夹":
                    needFixTop = true;
                    getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary));
                    return new FragmentCollection();
                case "推荐漫画":
                    needFixTop = true;
                    return FragmentRecmdIllust.newInstance("漫画");
                case "推荐小说":
                    needFixTop = true;
                    return new FragmentRecmdNovel();
                case "小说收藏":
                    needFixTop = true;
                    return FragmentLikeNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "小说作品":
                    needFixTop = true;
                    return FragmentUserNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "小说详情":
                    needFixTop = true;
                    return FragmentNovelHolder.newInstance((NovelBean) intent.getSerializableExtra(Params.CONTENT));
                case "图片详情":
                    needFixTop = true;
                    return FragmentImageDetail.newInstance(intent.getStringExtra(Params.URL));
                case "绑定邮箱":
                    needFixTop = false;
                    return new FragmentEditAccount();
                case "编辑个人资料":
                    needFixTop = false;
                    return new FragmentEditFile();
                case "热门直播":
                    needFixTop = true;
                    return new FragmentLive();
                case "标签屏蔽记录":
                    needFixTop = false;
                    return new FragmentMutedTags();
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
        return getIntent().getBooleanExtra("hideStatusBar", true);
    }

    /**
     *  这里要存储 needFixTop, 否则重构后顶部视图会进入状态栏
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("needFixTop", needFixTop);
    }
}
