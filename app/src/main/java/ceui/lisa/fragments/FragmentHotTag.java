package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.activities.SearchActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.TagAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyTagGridBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.TagItemDecoration;
import io.reactivex.Observable;


public class FragmentHotTag extends NetListFragment<FragmentBaseListBinding,
        ListTrendingtag, ListTrendingtag.TrendTagsBean> {

    private boolean isLoad = false;
    private String contentType = "";

    public static FragmentHotTag newInstance(String type) {
        Bundle args = new Bundle();
        args.putString(Params.CONTENT_TYPE, type);
        FragmentHotTag fragment = new FragmentHotTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        contentType = bundle.getString(Params.CONTENT_TYPE);
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
    public RemoteRepo<ListTrendingtag> repository() {
        return new RemoteRepo<ListTrendingtag>() {
            @Override
            public Observable<ListTrendingtag> initApi() {
                return Retro.getAppApi().getHotTags(token(), contentType);
            }

            @Override
            public Observable<ListTrendingtag> initNextApi() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<ListTrendingtag.TrendTagsBean, RecyTagGridBinding> adapter() {
        return new TagAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, SearchActivity.class);
                intent.putExtra(Params.KEY_WORD, allItems.get(position).getTag());
                intent.putExtra(Params.INDEX, Params.TYPE_ILLUST.equals(contentType) ? 0 : 1);
                startActivity(intent);
            }
        });
    }

    @Override
    public boolean showToolbar() {
        return false;
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
