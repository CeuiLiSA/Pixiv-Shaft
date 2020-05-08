package ceui.lisa.fragments;

import android.view.View;

import androidx.databinding.ViewDataBinding;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.utils.Common;

public abstract class LocalListFragment<Layout extends ViewDataBinding, Item>
        extends ListFragment<Layout, Item> {

    protected LocalRepo<List<Item>> mLocalRepo;

    @Override
    public void fresh() {
        if (mLocalRepo.first() != null && mLocalRepo.first().size() != 0) {
            List<Item> firstList = mLocalRepo.first();
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
        if (mLocalRepo.hasNext() &&
                mLocalRepo.next() != null &&
                mLocalRepo.next().size() != 0) {
            List<Item> nextList = mLocalRepo.next();
            if (mModel != null) {
                mModel.load(nextList);
            }
            onNextLoaded(nextList);
            mAdapter.notifyItemRangeInserted(getStartSize(), mLocalRepo.next().size());
        } else {
            if (mLocalRepo.showNoDataHint()) {
                Common.showToast("没有更多数据啦");
            }
        }
        mRefreshLayout.finishLoadMore(true);
    }

    @Override
    void initData() {
        mLocalRepo = (LocalRepo<List<Item>>) mBaseRepo;
    }
}
