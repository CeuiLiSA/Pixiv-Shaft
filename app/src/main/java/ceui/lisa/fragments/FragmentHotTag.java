package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.view.TagItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;


public class FragmentHotTag extends BaseListFragment<TrendingtagResponse, HotTagAdapter,
        TrendingtagResponse.TrendTagsBean> {

    private boolean isLoad = false;

    @Override
    Observable<TrendingtagResponse> initApi() {
        return Retro.getAppApi().getHotTags(sUserModel.getResponse().getAccess_token());
        //return null;
    }

    @Override
    Observable<TrendingtagResponse> initNextApi() {
        //热门标签没有下一页
        return null;
    }

    @Override
    void initData() {
        //啥事也不干
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
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                        allItems.get(position).getTag());
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                        "搜索结果");
                startActivity(intent);
            }
        });
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && !isLoad) {
            getFirstData();
            isLoad = true;
        }
    }
}
