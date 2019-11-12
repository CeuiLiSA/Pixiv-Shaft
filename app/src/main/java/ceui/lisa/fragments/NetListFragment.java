package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.ViewDataBinding;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

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
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import jp.wasabeef.recyclerview.animators.BaseItemAnimator;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

/**
 * 联网获取xx列表，
 *
 * @param <Layout>     这个列表的LayoutBinding
 * @param <Response>   这次请求的Response
 * @param <Item>       这个列表的单个Item实体类
 * @param <ItemLayout> 单个Item的LayoutBinding
 */
public abstract class NetListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item,
        ItemLayout extends ViewDataBinding> extends BaseBindFragment<Layout> {

    protected NetControl<Response> mNetControl;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected Response mResponse;
    protected BaseAdapter<Item, ItemLayout> mAdapter;
    protected List<Item> allItems = new ArrayList<>();
    protected String nextUrl;
    protected Toolbar mToolbar;
    private static final long animateDuration = 400L;

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
                mNetControl.getFooter(mContext) : new FalsifyFooter(mContext));
        mRefreshLayout.setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh(@NonNull RefreshLayout refreshLayout) {
                Common.showLog(className + "onRefresh ");
                mAdapter.clear();
                if (mNetControl.initApi() != null) {
                    mNetControl.getFirstData(new NullCtrl<Response>() {
                        @Override
                        public void success(Response response) {
                            mResponse = response;
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
                            firstSuccess();
                        }

                        @Override
                        public void must(boolean isSuccess) {
                            mRefreshLayout.finishRefresh(isSuccess);
                        }
                    });
                } else {
                    if (className.equals("FragmentR ")) {
                        showDataBase();
                    }
                }
            }
        });
        mRefreshLayout.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore(@NonNull RefreshLayout refreshLayout) {
                Common.showLog(className + "onLoadMore ");
                if (!TextUtils.isEmpty(nextUrl)) {
                    mNetControl.getNextData(new NullCtrl<Response>() {
                        @Override
                        public void success(Response response) {
                            mResponse = response;
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
        if (!(mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager)) {
            BaseItemAnimator baseItemAnimator = new LandingAnimator(new AnticipateOvershootInterpolator());
            baseItemAnimator.setAddDuration(animateDuration);
            baseItemAnimator.setRemoveDuration(animateDuration);
            baseItemAnimator.setMoveDuration(animateDuration);
            baseItemAnimator.setChangeDuration(animateDuration);
            mRecyclerView.setItemAnimator(baseItemAnimator);
        }
        if (autoRefresh()) {
            mRefreshLayout.autoRefresh();
        }
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

    public boolean autoRefresh() {
        return true;
    }

    public void firstSuccess() {
    }

    public void showDataBase() {
    }
}
