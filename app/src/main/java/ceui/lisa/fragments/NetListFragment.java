package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;

import androidx.databinding.ViewDataBinding;

import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.utils.Common;

/**
 * 联网获取xx列表，
 *
 * @param <Layout>     这个列表的LayoutBinding
 * @param <Response>   这次请求的Response
 * @param <Item>       这个列表的单个Item实体类
 */
public abstract class NetListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item> extends ListFragment<Layout, Item> {

    protected RemoteRepo<Response> mRemoteRepo;
    protected Response mResponse;

    @Override
    public void fresh() {
        if (mRemoteRepo.initApi() != null) {
            mRemoteRepo.getFirstData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    mResponse = response;
                    onResponse(mResponse);
                    if (response.getList() != null && response.getList().size() != 0) {
                        List<Item> firstList = response.getList();
                        beforeFirstLoad(firstList);
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
                    mModel.setNextUrl(response.getNextUrl());
                    if (!TextUtils.isEmpty(response.getNextUrl())) {
                        mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                    } else {
                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
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
            if (className.equals("FragmentRecmdIllust ")) {
                showDataBase();
            }
        }
    }

    @Override
    public void loadMore() {
        if (!TextUtils.isEmpty(mModel.getNextUrl())) {
            mRemoteRepo.getNextData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    mResponse = response;
                    if (response.getList() != null && response.getList().size() != 0) {
                        List<Item> nextList = response.getList();
                        beforeNextLoad(nextList);
                        if (mModel != null) {
                            mModel.load(nextList);
                        }
                        onNextLoaded(nextList);
                        mAdapter.notifyItemRangeInserted(getStartSize(), nextList.size());
                    }
                    mModel.setNextUrl(response.getNextUrl());
                }

                @Override
                public void must(boolean isSuccess) {
                    mRefreshLayout.finishLoadMore(isSuccess);
                }
            });
        } else {
            mRefreshLayout.finishLoadMore();
            if (mRemoteRepo.showNoDataHint()) {
                Common.showToast("没有更多数据啦");
            }
        }
    }

    @Override
    void initData() {
        mRemoteRepo = (RemoteRepo<Response>) mBaseRepo;
    }

    /**
     * FragmentR页面，调试过程中不需要每次都刷新，就调用这个方法来加载数据。只是为了方便测试
     */
    public void showDataBase() {
    }

    public void onResponse(Response response) {

    }
}
