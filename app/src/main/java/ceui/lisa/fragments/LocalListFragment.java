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
        emptyRela.setVisibility(View.INVISIBLE);
        if (mLocalRepo.first() != null && mLocalRepo.first().size() != 0) {
            List<Item> firstList = mLocalRepo.first();
            if (mModel != null) {
                mModel.load(firstList, true);
            }
            onFirstLoaded(firstList);
            mRecyclerView.setVisibility(View.VISIBLE);
            emptyRela.setVisibility(View.INVISIBLE);
            mAdapter.notifyItemRangeInserted(getStartSize(), firstList.size());
        } else {
            mRecyclerView.setVisibility(View.INVISIBLE);
            emptyRela.setVisibility(View.VISIBLE);
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
                mModel.load(nextList, false);
            }
            onNextLoaded(nextList);
            mAdapter.notifyItemRangeInserted(getStartSize(), mLocalRepo.next().size());
        } else {
            if (mLocalRepo.showNoDataHint()) {
                Common.showToast(getString(R.string.string_224));
            }
        }
        mRefreshLayout.finishLoadMore(true);
    }

    @Override
    protected void initData() {
        mLocalRepo = (LocalRepo<List<Item>>) mModel.getBaseRepo();
        super.initData();
    }
}
