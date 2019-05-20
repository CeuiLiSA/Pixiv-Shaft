package ceui.lisa.fragments;

import android.content.Intent;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.SearchResultActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.TagsBean;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.utils.AppBarStateChangeListener;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.GridItemDecoration;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;


public class FragmentHotTag extends BaseListFragment<TrendingtagResponse, HotTagAdapter,
        TrendingtagResponse.TrendTagsBean> {

    @Override
    Observable<TrendingtagResponse> initApi() {
        return Retro.getAppApi().getHotTags(mUserModel.getResponse().getAccess_token());
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
        mRecyclerView.addItemDecoration(new GridItemDecoration(3,
                DensityUtil.dp2px(8.0f), true));
        GridLayoutManager manager = new GridLayoutManager(mContext, 3);
        mRecyclerView.setLayoutManager(manager);
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
                Intent intent = new Intent(mContext, SearchResultActivity.class);
                intent.putExtra("key word", allItems.get(position).getTag());
                startActivity(intent);
            }
        });
    }
}
