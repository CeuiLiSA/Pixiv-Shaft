package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.ViewDataBinding;

import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.listener.OnLoadMoreListener;
import com.scwang.smartrefresh.layout.listener.OnRefreshListener;

import ceui.lisa.R;
import ceui.lisa.core.NetControl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.utils.Common;

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
        extends ListFragment<Layout, Item, ItemLayout> {

    protected NetControl<Response> mNetControl;
    protected Response mResponse;
    protected String nextUrl;

    @Override
    public void initView(View view) {
        super.initView(view);
        mNetControl = (NetControl<Response>) mBaseCtrl;
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
                    if (mNetControl.showNoDataHint()) {
                        Common.showToast("没有更多数据啦");
                    }
                }
            }
        });
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
