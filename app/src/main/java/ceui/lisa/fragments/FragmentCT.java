package ceui.lisa.fragments;

import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MenuAdapter;
import ceui.lisa.databinding.FragmentCtBinding;
import ceui.lisa.databinding.RecyMenuBinding;
import ceui.lisa.interfaces.BaseCtrl;
import ceui.lisa.interfaces.DataControl;
import ceui.lisa.model.MenuItem;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.GridItemDecoration;

public class FragmentCT extends LocalListFragment<FragmentCtBinding, MenuItem, RecyMenuBinding> {

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_ct;
    }

    @Override
    public BaseAdapter<MenuItem, RecyMenuBinding> adapter() {
        return new MenuAdapter(allItems, mContext);
    }

    @Override
    public BaseCtrl present() {
        return new DataControl<List<MenuItem>>() {
            @Override
            public List<MenuItem> first() {
                return Common.getMenuList();
            }

            @Override
            public List<MenuItem> next() {
                return null;
            }

            @Override
            public boolean enableRefresh() {
                return false;
            }

            @Override
            public boolean showNoDataHint() {
                return false;
            }
        };
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new GridLayoutManager(mContext, 2));
        mRecyclerView.addItemDecoration(new GridItemDecoration(2,
                DensityUtil.dp2px(16.0f), true));
        mRecyclerView.setHasFixedSize(true);
    }

    @Override
    public void onFirstLoaded(List<MenuItem> menuItems) {
        mRefreshLayout.setOnRefreshListener(null);
    }
}
