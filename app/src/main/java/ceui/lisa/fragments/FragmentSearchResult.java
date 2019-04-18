package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.GridItemDecoration;
import io.reactivex.Observable;

public class FragmentSearchResult extends BaseListFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list_grid;
    }

    private String keyWord = "";
    private String sort = "date_desc";
    private String searchTarget = "exact_match_for_tags";

    public static FragmentSearchResult newInstance(String keyWord){
        return newInstance(keyWord, "date_desc", "exact_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort){
        return newInstance(keyWord, sort, "exact_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort, String searchTarget){
        FragmentSearchResult fragmentSearchResult = new FragmentSearchResult();
        fragmentSearchResult.setKeyWord(keyWord);
        fragmentSearchResult.setSort(sort);
        fragmentSearchResult.setSearchTarget(searchTarget);
        return fragmentSearchResult;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        GridLayoutManager manager = new GridLayoutManager(mContext, 2);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(4.0f), false));
    }

    @Override
    String getToolbarTitle() {
        return keyWord;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().searchIllust(mUserModel.getResponse().getAccess_token(), keyWord, sort, searchTarget);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Shaft.allIllusts.clear();
                Shaft.allIllusts.addAll(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }

    public String getKeyWord() {
        return keyWord;
    }

    public void setKeyWord(String keyWord) {
        this.keyWord = keyWord;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getSearchTarget() {
        return searchTarget;
    }

    public void setSearchTarget(String searchTarget) {
        this.searchTarget = searchTarget;
    }
}
