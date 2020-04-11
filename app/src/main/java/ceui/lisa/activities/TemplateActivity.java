package ceui.lisa.activities;

import android.content.Intent;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityFragmentBinding;
import ceui.lisa.fragments.FragmentAboutApp;
import ceui.lisa.fragments.FragmentMultiDownld;
import ceui.lisa.fragments.FragmentRecmdIllust;
import ceui.lisa.fragments.FragmentSB;
import ceui.lisa.fragments.FragmentTest;
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
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseResult;

public class TemplateActivity extends BaseActivity<ActivityFragmentBinding> {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_OBJECT = "object";
    public static final String EXTRA_KEYWORD = "keyword";
    public static final String EXTRA_ILLUST_TITLE = "illust title";
    protected Fragment childFragment;

    protected Fragment createNewFragment() {
        Intent intent = getIntent();
        String dataType = intent.getStringExtra(EXTRA_FRAGMENT);

        if (dataType != null) {
            switch (dataType) {
                case "登录注册":
                    BarUtils.setNavBarColor(mActivity, getResources().getColor(R.color.colorPrimary));
                    return new FragmentLogin();
                case "搜索结果": {
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentSearchResult.newInstance(keyword);
                }
                case "相关作品": {
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(EXTRA_ILLUST_TITLE);
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
                    int id = intent.getIntExtra(Params.ILLUST_ID, 0);
                    String title = intent.getStringExtra(Params.ILLUST_TITLE);
                    return FragmentComment.newInstance(id, title);
                }
                case "账号管理":
                    return new FragmentLocalUsers();
                case "按标签筛选": {
                    String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                    return FragmentBookedTag.newInstance(keyword);
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
//                case "License":
//                    return new FragmentLicense();
                case "正在关注":
                    return FragmentFollowUser.newInstance(
                            getIntent().getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "好P友":
                    return new FragmentNiceFriend();
                case "搜索":
                    return new FragmentSearch();
                case "详细信息":
                    return new FragmentUserInfo();
                case "一言":
                    if(Dev.isDev){
                        //return new FragmentTest();
                    }else {
                        return new FragmentHitokoto();
                    }
                case "最新作品":
                    return new FragmentNew();
                case "粉丝":
                    return FragmentWhoFollowThisUser.newInstance(intent.getIntExtra(Params.USER_ID, 0));
                case "插画作品":
                    return FragmentUserIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "漫画作品":
                    return FragmentUserManga.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
                case "插画/漫画收藏":
                    return FragmentLikeIllust.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "下载管理":
                    return new FragmentDownload();
                case "收藏夹":
                    getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary));
                    return new FragmentCollection();
                case "推荐漫画":
                    return FragmentRecmdIllust.newInstance("漫画");
                case "推荐小说":
                    return new FragmentRecmdNovel();
                case "小说收藏":
                    return FragmentLikeNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            FragmentLikeIllust.TYPE_PUBLUC, true);
                case "小说作品":
                    return FragmentUserNovel.newInstance(intent.getIntExtra(Params.USER_ID, 0),
                            true);
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
}
