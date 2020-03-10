package ceui.lisa.fragments;

import android.text.TextUtils;
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
import com.scwang.smartrefresh.layout.header.FalsifyHeader;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.core.TagFilter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
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
        Response extends ListShow<Item>, Item, ItemLayout extends ViewDataBinding>
        extends BaseBindFragment<Layout> {

    public static final long animateDuration = 400L;
    protected NetControl<Response> mNetControl;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected ImageView noData;
    protected Response mResponse;
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
        noData = view.findViewById(R.id.no_data);
        initRecyclerView();
        mNetControl = present();
        mRefreshLayout.setRefreshHeader(mNetControl.enableRefresh() ?
                mNetControl.getHeader(mContext) : new FalsifyHeader(mContext));
        mRefreshLayout.setRefreshFooter(mNetControl.hasNext() ?
                mNetControl.getFooter(mContext) : new FalsifyFooter(mContext));
        noData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                noData.setVisibility(View.INVISIBLE);
                mRefreshLayout.autoRefresh();
            }
        });
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
                                mRecyclerView.setVisibility(View.VISIBLE);
                                noData.setVisibility(View.INVISIBLE);
                                int lastSize = allItems.size() + mAdapter.headerSize();
                                allItems.addAll(response.getList());
                                mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                            } else {
                                mRecyclerView.setVisibility(View.INVISIBLE);
                                noData.setVisibility(View.VISIBLE);
                                noData.setImageResource(R.mipmap.no_data_line);
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

                        @Override
                        public void onError(Throwable e) {
                            super.onError(e);
                            mRecyclerView.setVisibility(View.INVISIBLE);
                            noData.setVisibility(View.VISIBLE);
                            noData.setImageResource(R.mipmap.no_data_line);
                        }
                    });
                } else {
                    if (className.equals("FragmentRecmdManga ")) {
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
                                int lastSize = allItems.size() + mAdapter.headerSize();
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
                } else {
                    Common.showToast("没有更多数据啦");
                }
            }
        });
    }

    @Override
    public void initData() {
        mAdapter = adapter();
        if (mAdapter != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
        if (mRecyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager) {
            //do nothing
        } else {
            BaseItemAnimator baseItemAnimator = new LandingAnimator();
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

    /**
     * 指定是否显示Toolbar
     *
     * @return default true
     */
    public boolean showToolbar() {
        return true;
    }

    /**
     * 指定Toolbar title
     *
     * @return title
     */
    public String getToolbarTitle() {
        return "";
    }

    /**
     * 默认 LinearLayoutManager
     */
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    /**
     * 决定刚进入页面是否直接刷新，一般都是直接刷新，但是FragmentHotTag，不要直接刷新
     *
     * @return default true
     */
    public boolean autoRefresh() {
        return true;
    }

    public void nowRefresh() {
        mRecyclerView.smoothScrollToPosition(0);
        mRefreshLayout.autoRefresh();
    }

    /**
     * 第一波数据加载成功之后，FragmentR页面将数据写入到数据库，方法实际上没啥用，只是为了方便测试
     */
    public void firstSuccess() {
    }

    /**
     * FragmentR页面，调试过程中不需要每次都刷新，就调用这个方法来家在数据。只是为了方便测试
     */
    public void showDataBase() {
    }
}
