package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;

public abstract class NetListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item,
        ItemLayout extends ViewDataBinding> extends BaseBindFragment<Layout> {

    protected NetControl<Response> mNetControl;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected BaseAdapter<Item, ItemLayout> mAdapter;
    protected List<Item> allItems = new ArrayList<>();
    protected String nextUrl;
    protected Toolbar mToolbar;

    public abstract NetControl<Response> present();

    public abstract BaseAdapter<Item, ItemLayout> adapter();

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
    }

    @Override
    public void initView(View view) {
        mToolbar = view.findViewById(R.id.toolbar);
        if (mToolbar != null) {
            if (showToolbar()) {
                mToolbar.setVisibility(View.VISIBLE);
                mToolbar.setNavigationOnClickListener(v -> mActivity.finish());
                mToolbar.setTitle(getToolbarTitle());
            } else {
                mToolbar.setVisibility(View.GONE);
            }
        }
        mRecyclerView = view.findViewById(R.id.recyclerView);
        mRefreshLayout = view.findViewById(R.id.refreshLayout);
        initRecyclerView();
        mNetControl = present();
        mRefreshLayout.setRefreshHeader(mNetControl.enableRefresh() ?
                mNetControl.getHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mNetControl.hasNext() ?
                new ClassicsFooter(mContext) : new FalsifyFooter(mContext));
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                mAdapter.clear();
                mNetControl.getFirstData(new NullCtrl<Response>() {
                    @Override
                    public void success(Response response) {
                        if (response.getList() != null && response.getList().size() != 0) {
                            int lastSize = allItems.size();
                            allItems.addAll(response.getList());
                            mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                        }
                        nextUrl = response.getNextUrl();
                        if (!TextUtils.isEmpty(nextUrl)) {
                            mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                        } else {
                            mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                        }
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        mRefreshLayout.finishRefresh(isSuccess);
                    }
                });
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                if (!TextUtils.isEmpty(nextUrl)) {
                    mNetControl.getNextData(new NullCtrl<Response>() {
                        @Override
                        public void success(Response response) {
                            if (response.getList() != null && response.getList().size() != 0) {
                                int lastSize = allItems.size();
                                allItems.addAll(response.getList());
                                mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                            }
                            nextUrl = response.getNextUrl();
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            mRefreshLayout.finishLoadMore(isSuccess);
                        }
                    });
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

    public boolean showToolbar() {
        return true;
    }

    public String getToolbarTitle() {
        return "";
    }

    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }
}
