package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayoutManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.TempTokenResponse;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.GridScrollChangeManager;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.utils.Settings.ALL_SIZE;

/**
 * 搜索插画结果
 */
public class FragmentSearchResult extends AutoClipFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    private String token = "";
    private String keyWord = "";
    private String starSize = "";
    private String sort = "date_desc";
    private String searchTarget = "partial_match_for_tags";

    public static FragmentSearchResult newInstance(String keyWord){
        return newInstance(keyWord, "date_desc", "partial_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort){
        return newInstance(keyWord, sort, "partial_match_for_tags");
    }

    public static FragmentSearchResult newInstance(String keyWord, String sort, String searchTarget){
        FragmentSearchResult fragmentSearchResult = new FragmentSearchResult();
        fragmentSearchResult.setKeyWord(keyWord);
        fragmentSearchResult.setSort(sort);
        fragmentSearchResult.setSearchTarget(searchTarget);
        fragmentSearchResult.starSize = Shaft.sSettings.getSearchFilter().contains("无限制") ?
                "" : (Shaft.sSettings.getSearchFilter() + "user") ;
        return fragmentSearchResult;
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateFragmentActivity)getActivity()).setSupportActionBar(mToolbar);
        mToolbar.setTitle(getToolbarTitle());
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().finish();
            }
        });
        token = sUserModel.getResponse().getAccess_token();
        return v;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        GridScrollChangeManager manager = new GridScrollChangeManager(mContext, 2);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(4.0f), false));
    }

    @Override
    String getToolbarTitle() {
        return keyWord;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().searchIllust(token, keyWord + starSize, sort, searchTarget);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(token, nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.select_star_size, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setTitle("被收藏数");
            builder.setItems(ALL_SIZE, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    starSize = ALL_SIZE[which].contains("无限制") ? "" : ALL_SIZE[which];
                    sort = "date_desc";
                    token = sUserModel.getResponse().getAccess_token();
                    getFirstData();
                }
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.show();
        }else if(item.getItemId() == R.id.action_rank){
            getRankToken();
        }else if(item.getItemId() == R.id.action_new){
            sort = "date_desc";
            starSize = "";
            token = sUserModel.getResponse().getAccess_token();
            getFirstData();
        }else if(item.getItemId() == R.id.action_old){
            sort = "date_asc";
            starSize = "";
            token = sUserModel.getResponse().getAccess_token();
            getFirstData();
        }
        return super.onOptionsItemSelected(item);
    }

    private void getRankToken(){
        if(sUserModel.getResponse().getUser().isIs_premium()){
            sort = "popular_desc";
            starSize = "";
            getFirstData();
        }else {
            Retro.getRankApi().getRankToken()
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<TempTokenResponse>() {
                        @Override
                        public void onNext(TempTokenResponse tempTokenResponse) {
                            if (tempTokenResponse != null) {
                                token = "Bearer " + tempTokenResponse.getToken();
                                sort = "popular_desc";
                                starSize = "";
                                getFirstData();
                            }
                        }
                    });
        }
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
