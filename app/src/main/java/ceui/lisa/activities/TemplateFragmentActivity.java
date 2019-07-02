package ceui.lisa.activities;

import android.content.Intent;
import android.support.v4.app.Fragment;
import android.view.KeyEvent;

import ceui.lisa.fragments.FragmentComment;
import ceui.lisa.fragments.FragmentDrag;
import ceui.lisa.fragments.FragmentLocalUsers;
import ceui.lisa.fragments.FragmentMetro;
import ceui.lisa.fragments.FragmentPivision;
import ceui.lisa.fragments.FragmentRecmdUser;
import ceui.lisa.fragments.FragmentRelatedIllust;
import ceui.lisa.fragments.FragmentSearchResult;
import ceui.lisa.fragments.FragmentSearchUser;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentViewHistory;
import ceui.lisa.fragments.FragmentWebView;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ReverseResult;

public class TemplateFragmentActivity extends FragmentActivity {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    public static final String EXTRA_ILLUST_ID = "illust id";
    public static final String EXTRA_ILLUST_TITLE = "illust title";

    @Override
    protected Fragment createNewFragment() {
        Intent intent = getIntent();
        String dataType = intent.getStringExtra(EXTRA_FRAGMENT);

        if (dataType != null) {
            if (dataType.equals("搜索结果")) {
                String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                return FragmentSearchResult.newInstance(keyword);
            } else if (dataType.equals("相关作品")) {
                int id = intent.getIntExtra(EXTRA_ILLUST_ID, 0);
                String title = intent.getStringExtra(EXTRA_ILLUST_TITLE);
                return FragmentRelatedIllust.newInstance(id, title);
            } else if (dataType.equals("浏览记录")) {
                return new FragmentViewHistory();
            } else if (dataType.equals("网页链接")) {
                String url = intent.getStringExtra("url");
                return FragmentWebView.newInstance("PixiVision特辑", url);
            } else if (dataType.equals("设置")) {
                return new FragmentSettings();
            } else if (dataType.equals("推荐用户")) {
                return new FragmentRecmdUser();
            } else if (dataType.equals("特辑")) {
                return new FragmentPivision();
            } else if (dataType.equals("拖动测试")) {
                return new FragmentDrag();
            } else if (dataType.equals("搜索用户")) {
                String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                return FragmentSearchUser.newInstance(keyword);
            }else if (dataType.equals("以图搜图")) {
                ReverseResult result = intent.getParcelableExtra("result");
                return FragmentWebView.newInstance(result.getTitle(),result.getUrl(),result.getResponseBody(),result.getMime(),result.getEncoding(),result.getHistory_url());
            }else if(dataType.equals("相关评论")){
                int id = intent.getIntExtra(EXTRA_ILLUST_ID, 0);
                String title = intent.getStringExtra(EXTRA_ILLUST_TITLE);
                return FragmentComment.newInstance(id, title);
            }else if (dataType.equals("账号管理")) {
                return new FragmentLocalUsers();
            }else if (dataType.equals("地铁表白器")) {
                return new FragmentMetro();
            }
        }
        return null;
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(childFragment instanceof FragmentWebView){
            if (((FragmentWebView) childFragment).getAgentWeb().handleKeyEvent(keyCode, event)) {
                return true;
            }else {
                return super.onKeyDown(keyCode, event);
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
