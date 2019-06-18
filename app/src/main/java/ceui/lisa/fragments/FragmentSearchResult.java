package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.widget.GridLayoutManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.dialogs.SelectStartSizeDialog;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.NullResponse;
import ceui.lisa.response.RankTokenResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.utils.IllustChannel;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 搜索插画结果
 */
public class FragmentSearchResult extends BaseListFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    private String token = "";
    private String keyWord = "";
    private String starSize = " 10000";
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
        return fragmentSearchResult;
    }

    @Override
    View initView(View v) {
        super.initView(v);
        ((TemplateFragmentActivity)getActivity()).setSupportActionBar(mToolbar);
        mToolbar.setTitle(getToolbarTitle());
        token = mUserModel.getResponse().getAccess_token();
        return v;
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
        return Retro.getAppApi().searchIllust(token, keyWord + starSize, sort, searchTarget);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(token, nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
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
        EventBus.getDefault().register(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.select_star_size, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            SelectStartSizeDialog dialog = new SelectStartSizeDialog();
            dialog.show(getChildFragmentManager(), "SelectStartSizeDialog");
        }else if(item.getItemId() == R.id.action_rank){
            getRankToken();
        }
        return super.onOptionsItemSelected(item);
    }

    private void getRankToken(){
        if(mUserModel.getResponse().getUser().isIs_premium()){
            getFirstData();
        }else {
            Retro.getRankApi().getRankToken(mUserModel.getResponse().getUser().getId())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<RankTokenResponse>() {
                        @Override
                        public void onNext(RankTokenResponse rankTokenResponse) {
                            if (rankTokenResponse != null) {
                                token = "Bearer " + rankTokenResponse.getAuth();
                                sort = "popular_desc";
                                getFirstData();
                            }
                        }
                    });
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if(className.contains(event.getReceiver())) {
            Common.showLog(className + "EVENTBUS 接受了消息");
            starSize = (String) event.getObject();
            getFirstData();
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
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
