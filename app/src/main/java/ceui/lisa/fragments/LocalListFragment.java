package ceui.lisa.fragments;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

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
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

import static ceui.lisa.fragments.NetListFragment.animateDuration;

public abstract class LocalListFragment<Layout extends ViewDataBinding, Item,
        ItemLayout extends ViewDataBinding> extends ListFragment<Layout, Item, ItemLayout> {

    protected DataControl<List<Item>> mDataControl;

    @Override
    public void initView(View view) {
        super.initView(view);
        mDataControl = ((DataControl<List<Item>>) mBaseCtrl);
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                if (mDataControl.enableRefresh()) {
                    if (mDataControl.first() != null && mDataControl.first().size() != 0) {
                        mAdapter.clear();
                        int lastSize = allItems.size();
                        allItems.addAll(mDataControl.first());
                        mAdapter.notifyItemRangeInserted(lastSize, mDataControl.first().size());
                        mRecyclerView.setVisibility(View.VISIBLE);
                        noData.setVisibility(View.INVISIBLE);
                    } else {
                        mRecyclerView.setVisibility(View.INVISIBLE);
                        noData.setVisibility(View.VISIBLE);
                        noData.setImageResource(R.mipmap.no_data_line);
                    }
                    mRefreshLayout.finishRefresh(true);
                }
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                if (mDataControl.hasNext()) {
                    mAdapter.clear();
                    int lastSize = allItems.size();
                    allItems.addAll(mDataControl.next());
                    mAdapter.notifyItemRangeInserted(lastSize, mDataControl.next().size());
                } else {
                    Common.showToast("没有更多数据啦");
                }
                mRefreshLayout.finishLoadMore(true);
            }
        });
    }
}
