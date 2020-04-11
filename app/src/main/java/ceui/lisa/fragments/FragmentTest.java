package ceui.lisa.fragments;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.IView;

public abstract class FragmentTest<Response extends ListShow<Item>, Item,
        ItemView extends ViewDataBinding> extends BaseFragment<FragmentTestBinding> implements
        IView<Response> {

    protected List<Item> allItems = new ArrayList<>();

    private BaseAdapter<Item, ItemView> mAdapter;
    private IPresent<Response> mPresent;

    public abstract IPresent<Response> present();

    public abstract BaseAdapter<Item, ItemView> adapter();

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_test;
    }

    @Override
    public void initView(View view) {
        mPresent = present();
        mPresent.attach(this);
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.setHasFixedSize(true);
        mAdapter = adapter();
        baseBind.recyclerView.setAdapter(mAdapter);
        baseBind.refreshLayout.setEnableRefresh(true);
        baseBind.refreshLayout.setRefreshHeader(new MaterialHeader(mContext));
        baseBind.refreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                mPresent.first();
            }
        });
        baseBind.refreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                mPresent.next();
            }
        });
        baseBind.refreshLayout.autoRefresh();
    }

    @Override
    public void onDestroyView() {
        mPresent.dettach();
        super.onDestroyView();
    }

    @Override
    public void loadFirst(Response data) {
        int startSize = allItems.size();
        allItems.addAll(data.getList());
        mAdapter.notifyItemRangeInserted(startSize, data.getList().size());
        baseBind.refreshLayout.finishRefresh();
    }

    @Override
    public void loadNext(Response data) {
        int startSize = allItems.size();
        allItems.addAll(data.getList());
        mAdapter.notifyItemRangeInserted(startSize, data.getList().size());
        baseBind.refreshLayout.finishLoadMore();
    }

    @Override
    public void noData() {

    }

    @Override
    public void error() {

    }

    @Override
    public void clearData() {
        mAdapter.clear();
    }
}
