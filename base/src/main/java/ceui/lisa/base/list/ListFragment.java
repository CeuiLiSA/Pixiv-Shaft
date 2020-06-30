package ceui.lisa.base.list;

import android.view.View;

import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.base.BaseFragment;
import ceui.lisa.base.R;

public abstract class ListFragment<Layout extends ViewDataBinding, Item> extends BaseFragment<Layout> {

    protected BaseAdapter<Item, ? extends ViewDataBinding> mAdapter;
    protected List<Item> allItems = new ArrayList<>();
    protected RecyclerView mRecyclerView;

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_list;
    }

    public abstract BaseAdapter<Item, ? extends ViewDataBinding> adapter();

    @Override
    protected void initView() {
        Toolbar toolbar = rootView.findViewById(R.id.toolbar);
        if (toolbar != null) {
            if (showToolbar()) {
                toolbar.setVisibility(View.VISIBLE);
                toolbar.setTitle(getToolbarTitle());
            } else {
                toolbar.setVisibility(View.GONE);
            }
        }

        mRecyclerView = rootView.findViewById(R.id.recyclerView);
        if (mRecyclerView != null) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
            mRecyclerView.setHasFixedSize(true);
            mAdapter = adapter();
            if (mAdapter != null) {
                mRecyclerView.setAdapter(mAdapter);
            }
        }

    }

    public boolean showToolbar() {
        return true;
    }

    public String getToolbarTitle() {
        return " ";
    }

    public abstract void first();

    public abstract void next();
}
