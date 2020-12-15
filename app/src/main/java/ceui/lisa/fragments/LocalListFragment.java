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
        List<Item> firstList = mLocalRepo.first();
        if (!Common.isEmpty(firstList)) {
            if (mModel != null) {
                mModel.load(firstList, true);
                allItems = mModel.getContent();
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
        List<Item> nextList = mLocalRepo.next();
        if (mLocalRepo.hasNext() && !Common.isEmpty(nextList)) {
            if (mModel != null) {
                mModel.load(nextList, false);
                allItems = mModel.getContent();
            }
            onNextLoaded(nextList);
            mAdapter.notifyItemRangeInserted(getStartSize(), nextList.size());
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
