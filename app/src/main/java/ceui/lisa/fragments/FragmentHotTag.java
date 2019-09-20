package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.HAdapter;
import ceui.lisa.databinding.RecyTagGridBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.TagItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;


public class FragmentHotTag extends FragmentList<TrendingtagResponse, TrendingtagResponse.TrendTagsBean,
        RecyTagGridBinding> {

    private boolean isLoad = false;

    @Override
    public Observable<TrendingtagResponse> initApi() {
        return Retro.getAppApi().getHotTags(sUserModel.getResponse().getAccess_token());
    }

    @Override
    public Observable<TrendingtagResponse> initNextApi() {
        //热门标签没有下一页
        return null;
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 3);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position == 0) {
                    return 3;
                } else {
                    return 1;
                }
            }
        });
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new TagItemDecoration(
                3, DensityUtil.dp2px(1.0f), false));
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initAdapter() {
        mAdapter = new HAdapter(allItems, mContext);
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
            baseBind.refreshLayout.autoRefresh();
            isLoad = true;
        }
    }

    @Override
    public boolean autoRefresh() {
        return false;
    }
}
