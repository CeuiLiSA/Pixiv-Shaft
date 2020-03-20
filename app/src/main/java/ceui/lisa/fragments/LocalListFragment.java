package ceui.lisa.fragments;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfaces.DataControl;
import ceui.lisa.utils.Common;

public abstract class LocalListFragment<Layout extends ViewDataBinding, Item,
        ItemLayout extends ViewDataBinding> extends ListFragment<Layout, Item, ItemLayout> {

    protected DataControl<List<Item>> mDataControl;

    @Override
    public void initView(View view) {
        super.initView(view);
        mDataControl = (DataControl<List<Item>>) mBaseCtrl;
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                Common.showLog(className + "onRefresh ");
                mAdapter.clear();
                if (mDataControl.first() != null && mDataControl.first().size() != 0) {
                    int lastSize = allItems.size();
                    List<Item> firstList = mDataControl.first();
                    allItems.addAll(firstList);
                    onFirstLoaded(firstList);
                    mRecyclerView.setVisibility(View.VISIBLE);
                    noData.setVisibility(View.INVISIBLE);
                    mAdapter.notifyItemRangeInserted(lastSize, mDataControl.first().size());
                } else {
                    mRecyclerView.setVisibility(View.INVISIBLE);
                    noData.setVisibility(View.VISIBLE);
                    noData.setImageResource(R.mipmap.no_data_line);
                }
                mRefreshLayout.finishRefresh(true);
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                if (mDataControl.hasNext() &&
                        mDataControl.next() != null &&
                        mDataControl.next().size() != 0) {
                    int lastSize = allItems.size();
                    List<Item> nextList = mDataControl.next();
                    allItems.addAll(nextList);
                    onNextLoaded(nextList);
                    mAdapter.notifyItemRangeInserted(lastSize, mDataControl.next().size());
                } else {
                    if (mDataControl.showNoDataHint()) {
                        Common.showToast("没有更多数据啦");
                    }
                }
                mRefreshLayout.finishLoadMore(true);
            }
        });
    }
}
