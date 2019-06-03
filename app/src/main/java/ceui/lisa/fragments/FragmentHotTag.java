package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.network.Retro;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.utils.TagItemDecoration;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;


public class FragmentHotTag extends BaseListFragment<TrendingtagResponse, HotTagAdapter,
        TrendingtagResponse.TrendTagsBean> {

    @Override
    Observable<TrendingtagResponse> initApi() {
        //return Retro.getAppApi().getHotTags(mUserModel.getResponse().getAccess_token());
        return null;
    }

    @Override
    Observable<TrendingtagResponse> initNextApi() {
        //热门标签没有下一页
        return null;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    boolean hasNext() {
        return false;
    }

    @Override
    void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 3);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if(position == 0) {
                    return 3;
                }
                else {
                    return 1;
                }
            }
        });
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new TagItemDecoration(
                3, DensityUtil.dp2px( 1.0f), false));
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initAdapter() {
        mAdapter = new HotTagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener((v, position, viewType) -> {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                    allItems.get(position).getTag());
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                    "搜索结果");
            startActivity(intent);
        });
    }

    @Override
    void initData() {
        getFirstData();
    }

    public void getFirstData() {
        Retro.getAppApi().getHotTags(mUserModel.getResponse().getAccess_token()).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<TrendingtagResponse>(){

                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(TrendingtagResponse trendingtagResponse) {
                        if(trendingtagResponse != null){
                            allItems.clear();
                            allItems.addAll(trendingtagResponse.getList());
                            mAdapter = new HotTagAdapter(allItems, mContext);
                            mAdapter.setOnItemClickListener((v, position, viewType) -> {
//                                TODO START TRNDINGTAG ACTIVITY
//                                    Intent intent = new Intent(mContext, PikaActivity.class);
//                                    intent.putExtra("user id", allItems.get(position).getUser().getId());
//                                    startActivity(intent);
                            });
                            mRecyclerView.setAdapter(mAdapter);
                        }
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                } );
    }
}
