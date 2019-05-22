package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.HotTagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.utils.GridItemDecoration;
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
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                        allItems.get(position).getTag());
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                        "搜索结果");
                startActivity(intent);
            }
        });
    }
}
