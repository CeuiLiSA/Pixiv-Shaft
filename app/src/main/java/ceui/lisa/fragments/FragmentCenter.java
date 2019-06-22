package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v4.app.FragmentTransaction;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.mancj.materialsearchbar.MaterialSearchBar;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;

import static ceui.lisa.activities.Shaft.mUserModel;

public class FragmentCenter extends BaseFragment {

    private boolean isLoad = false;
    private MaterialSearchBar mSearchBar;
    private int searchType = 0;

    public FragmentCenter() {
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    View initView(View v) {
        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        head.setLayoutParams(headParams);

        mSearchBar = v.findViewById(R.id.searchBar);
        mSearchBar.inflateMenu(R.menu.search_menu);
        mSearchBar.getMenu().setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_1:
                    if (searchType != 0) {
                        mSearchBar.setPlaceHolder("标签搜作品");
                        searchType = 0;
                    }
                    break;
                case R.id.action_2:
                    if (searchType != 1) {
                        mSearchBar.setPlaceHolder("ID搜作品");
                        searchType = 1;
                    }
                    break;
                case R.id.action_3:
                    if (searchType != 2) {
                        mSearchBar.setPlaceHolder("关键字搜画师");
                        searchType = 2;
                    }
                    break;
                case R.id.action_4:
                    if (searchType != 3) {
                        mSearchBar.setPlaceHolder("ID搜画师");
                        searchType = 3;
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        mSearchBar.setOnSearchActionListener(new MaterialSearchBar.OnSearchActionListener() {
            @Override
            public void onSearchStateChanged(boolean enabled) {

            }

            @Override
            public void onSearchConfirmed(CharSequence text) {
                String keyWord = String.valueOf(text);
                if (!TextUtils.isEmpty(keyWord)) {

                    if (searchType == 0) {
                        Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD, keyWord);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                                "搜索结果");
                        startActivity(intent);
                    } else if (searchType == 1) {
                        if(isNumeric(keyWord)){
                            PixivOperate.getIllustByID(mUserModel, Integer.valueOf(keyWord), mContext);
                        }else {
                            Common.showToast("ID必须为全数字");
                        }
                    } else if (searchType == 2) {
                        Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                                keyWord);
                        intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                                "搜索用户");
                        startActivity(intent);
                    } else if (searchType == 3) {
                        if(isNumeric(keyWord)){
                            Intent intent = new Intent(mContext, UserDetailActivity.class);
                            intent.putExtra("user id", Integer.valueOf(keyWord));
                            startActivity(intent);
                        }else {
                            Common.showToast("ID必须为全数字");
                        }
                    }
                } else {
                    Common.showToast("请输入关键字");
                }
            }

            @Override
            public void onButtonClicked(int buttonCode) {

            }
        });
        TextView textView = v.findViewById(R.id.see_more);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                startActivity(intent);
            }
        });

        FragmentRankHorizontal fragmentRankHorizontal = new FragmentRankHorizontal();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, fragmentRankHorizontal).commit();
        return v;
    }

    @Override
    void initData() {

    }


    public static boolean isNumeric(String str) {
        for (int i = str.length(); --i >= 0; ) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && !isLoad) {
            FragmentPivisionHorizontal fragmentPivision = new FragmentPivisionHorizontal();
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_pivision, fragmentPivision).commit();
            isLoad = true;
        }
    }
}
