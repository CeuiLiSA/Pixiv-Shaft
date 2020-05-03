package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.TagAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyTagGridBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.TagItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;


public class FragmentHotTag extends NetListFragment<FragmentBaseListBinding,
        ListTrendingtag, ListTrendingtag.TrendTagsBean, RecyTagGridBinding> {

    private boolean isLoad = false;

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
    public NetControl<ListTrendingtag> present() {
        return new NetControl<ListTrendingtag>() {
            @Override
            public Observable<ListTrendingtag> initApi() {
                return Retro.getAppApi().getHotTags(token(), Params.TYPE_ILLUST);
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
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                        allItems.get(position).getTag());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索结果");
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

    @Override
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        nowRefresh();
    }
}
