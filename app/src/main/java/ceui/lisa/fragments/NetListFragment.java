package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.view.View;

import androidx.databinding.ViewDataBinding;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.SimpleUserAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.Starable;
import ceui.lisa.notification.CommonReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

/**
 * 联网获取xx列表，
 *
 * @param <Layout>   这个列表的LayoutBinding
 * @param <Response> 这次请求的Response
 * @param <Item>     这个列表的单个Item实体类
 */
public abstract class NetListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item> extends ListFragment<Layout, Item> {

    protected RemoteRepo<Response> mRemoteRepo;
    protected Response mResponse;
    protected BroadcastReceiver mReceiver = null;

    @Override
    public void fresh() {
        if (mRemoteRepo.initApi() != null) {
            mRemoteRepo.getFirstData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    mResponse = response;
                    onResponse(mResponse);
                    if (mResponse.getList() != null && mResponse.getList().size() != 0) {
                        List<Item> firstList = mResponse.getList();
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
                    mModel.setNextUrl(mResponse.getNextUrl());
                    if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
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
            showDataBase();
        }
    }

    @Override
    public void loadMore() {
        if (!TextUtils.isEmpty(mModel.getNextUrl())) {
            mRemoteRepo.getNextData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    mResponse = response;
                    if (mResponse.getList() != null && mResponse.getList().size() != 0) {
                        List<Item> nextList = mResponse.getList();
                        beforeNextLoad(nextList);
                        if (mModel != null) {
                            mModel.load(nextList);
                        }
                        onNextLoaded(nextList);
                        mAdapter.notifyItemRangeInserted(getStartSize(), nextList.size());
                    }
                    mModel.setNextUrl(mResponse.getNextUrl());
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
    protected void initData() {
        mRemoteRepo = (RemoteRepo<Response>) mModel.getBaseRepo();
    }

    /**
     * FragmentR页面，调试过程中不需要每次都刷新，就调用这个方法来加载数据。只是为了方便测试
     */
    public void showDataBase() {
    }

    public void onResponse(Response response) {

    }

    @Override
    public void onAdapterPrepared() {
        super.onAdapterPrepared();

        //注册本地广播
        if (mAdapter instanceof IAdapter || mAdapter instanceof EventAdapter) {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
            intentFilter.addAction(Params.LIKED_ILLUST);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        } else if (mAdapter instanceof UAdapter || mAdapter instanceof SimpleUserAdapter) {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
            intentFilter.addAction(Params.LIKED_USER);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        } else if (mAdapter instanceof NAdapter) {
            IntentFilter intentFilter = new IntentFilter();
            mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
            intentFilter.addAction(Params.LIKED_NOVEL);
            LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
        }
    }
}
