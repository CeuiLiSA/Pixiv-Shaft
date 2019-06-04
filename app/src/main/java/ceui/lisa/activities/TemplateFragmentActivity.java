package ceui.lisa.activities;

import android.content.Intent;
import android.support.v4.app.Fragment;

import ceui.lisa.fragments.FragmentPivision;
import ceui.lisa.fragments.FragmentRecmdUser;
import ceui.lisa.fragments.FragmentRelatedIllust;
import ceui.lisa.fragments.FragmentSearchResult;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentViewHistory;
import ceui.lisa.fragments.FragmentWebView;

public class TemplateFragmentActivity extends FragmentActivity {

    public static final String EXTRA_FRAGMENT = "dataType";
    public static final String EXTRA_KEYWORD = "keyword";
    public static final String EXTRA_ILLUST_ID = "illust id";
    public static final String EXTRA_ILLUST_TITLE = "illust title";

    @Override
    protected Fragment createNewFragment() {
        Intent intent = getIntent();
        String dataType = intent.getStringExtra(EXTRA_FRAGMENT);

        if(dataType != null){
            if(dataType.equals("搜索结果")){
                String keyword = intent.getStringExtra(EXTRA_KEYWORD);
                return FragmentSearchResult.newInstance(keyword);
            }else if(dataType.equals("相关作品")){
                int id = intent.getIntExtra(EXTRA_ILLUST_ID, 0);
                String title = intent.getStringExtra(EXTRA_ILLUST_TITLE);
                return FragmentRelatedIllust.newInstance(id, title);
            }else if(dataType.equals("浏览记录")){
                return new FragmentViewHistory();
            }else if(dataType.equals("网页链接")){
                String url = intent.getStringExtra("url");
                return FragmentWebView.newInstance("特辑", url);
            }else if(dataType.equals("设置")){
                return new FragmentSettings();
            }else if(dataType.equals("特辑")){
                return new FragmentPivision();
            }
            else if(dataType.equals("推荐用户")){
                return new FragmentRecmdUser();
            }
        }
        return null;
    }
}
