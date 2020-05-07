package ceui.lisa.fragments;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.DataControl;
import ceui.lisa.utils.Common;

public abstract class LocalListFragment<Layout extends ViewDataBinding, Item>
        extends ListFragment<Layout, Item> {

    protected DataControl<List<Item>> mDataControl;

    @Override
    public void fresh() {
        if (mDataControl.first() != null && mDataControl.first().size() != 0) {
            List<Item> firstList = mDataControl.first();
            if (mModel != null) {
                mModel.load(firstList);
            }
            onFirstLoaded(firstList);
            mRecyclerView.setVisibility(View.VISIBLE);
            noData.setVisibility(View.INVISIBLE);
            mAdapter.notifyItemRangeInserted(getStartSize(), firstList.size());
        } else {
            mRecyclerView.setVisibility(View.INVISIBLE);
            noData.setVisibility(View.VISIBLE);
            noData.setImageResource(R.mipmap.no_data_line);
        }
        mRefreshLayout.finishRefresh(true);
    }

    @Override
    public void loadMore() {
        if (mDataControl.hasNext() &&
                mDataControl.next() != null &&
                mDataControl.next().size() != 0) {
            List<Item> nextList = mDataControl.next();
            if (mModel != null) {
                mModel.load(nextList);
            }
            onNextLoaded(nextList);
            mAdapter.notifyItemRangeInserted(getStartSize(), mDataControl.next().size());
        } else {
            if (mDataControl.showNoDataHint()) {
                Common.showToast("没有更多数据啦");
            }
        }
        mRefreshLayout.finishLoadMore(true);
    }

    @Override
    void initData() {
        mDataControl = (DataControl<List<Item>>) mBaseCtrl;
    }
}
