package ceui.lisa.fragments;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.interfaces.DataControl;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;

public abstract class LocalListFragment<Layout extends ViewDataBinding, Item,
        ItemLayout extends ViewDataBinding> extends BaseBindFragment<Layout> {

    protected DataControl<List<Item>> mDataControl;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected BaseAdapter<Item, ItemLayout> mAdapter;
    protected List<Item> allItems = new ArrayList<>();
    protected String nextUrl;

    public abstract DataControl<List<Item>> present();

    public abstract BaseAdapter<Item, ItemLayout> adapter();

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    @Override
    public void initView(View view) {
        mRecyclerView = view.findViewById(R.id.recyclerView);
        mRefreshLayout = view.findViewById(R.id.refreshLayout);
        initRecyclerView();
        mDataControl = present();
        mRefreshLayout.setRefreshHeader(mDataControl.enableRefresh() ?
                new ClassicsHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mDataControl.hasNext() ?
                new ClassicsFooter(mContext) : new FalsifyFooter(mContext));
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                if (mDataControl.enableRefresh()) {
                    mAdapter.clear();
                    int lastSize = allItems.size();
                    allItems.addAll(mDataControl.first());
                    mAdapter.notifyItemRangeInserted(lastSize, mDataControl.first().size());
                    mRefreshLayout.finishRefresh(true);
                }
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                if (mDataControl.hasNext()) {
                    int lastSize = allItems.size();
                    allItems.addAll(mDataControl.next());
                    mAdapter.notifyItemRangeInserted(lastSize, mDataControl.next().size());
                    mRefreshLayout.finishLoadMore(true);
                }
            }
        });
    }

    @Override
    public void initData() {
        mAdapter = adapter();
        mRecyclerView.setAdapter(mAdapter);
        mRefreshLayout.autoRefresh();
    }

    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }
}
