package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.CallSuper;
import androidx.databinding.ViewDataBinding;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

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
        if (!mRemoteRepo.localData()) {
            emptyRela.setVisibility(View.INVISIBLE);
            mRemoteRepo.getFirstData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    Common.showLog("trace 000");
                    if (!isAdded()) {
                        return;
                    }
                    Common.showLog("trace 111");
                    mResponse = response;
                    tryCatchResponse(mResponse);
                    if (!Common.isEmpty(mResponse.getList())) {
                        Common.showLog("trace 222 " + mAdapter.getItemCount());
                        beforeFirstLoad(mResponse.getList());
                        mModel.load(mResponse.getList(), true);
                        allItems = mModel.getContent();
                        onFirstLoaded(mResponse.getList());
                        mRecyclerView.setVisibility(View.VISIBLE);
                        emptyRela.setVisibility(View.INVISIBLE);
                        mAdapter.notifyItemRangeInserted(getStartSize(), mResponse.getList().size());
                        Common.showLog("trace 777 " + mAdapter.getItemCount() + " allItems.size():" + allItems.size() + " modelSize:" + mModel.getContent().size());
                    } else {
                        Common.showLog("trace 333");
                        mRecyclerView.setVisibility(View.INVISIBLE);
                        emptyRela.setVisibility(View.VISIBLE);
                    }
                    Common.showLog("trace 444");
                    mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                    if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                        Common.showLog("trace 555");
                        mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                    } else {
                        Common.showLog("trace 666");
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
                    emptyRela.setVisibility(View.VISIBLE);
                }
            });
        } else {
            showDataBase();
        }
    }

    private void tryCatchResponse(Response response) {
        try {
            onResponse(response);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void loadMore() {
        if (!TextUtils.isEmpty(mRemoteRepo.getNextUrl())) {
            mRemoteRepo.getNextData(new NullCtrl<Response>() {
                @Override
                public void success(Response response) {
                    if (!isAdded()) {
                        return;
                    }
                    mResponse = response;
                    if (!Common.isEmpty(mResponse.getList())) {
                        beforeNextLoad(mResponse.getList());
                        mModel.load(mResponse.getList(), false);
                        allItems = mModel.getContent();
                        onNextLoaded(mResponse.getList());
                        mAdapter.notifyItemRangeInserted(getStartSize(), mResponse.getList().size());
                    }
                    mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                    if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                        mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                    } else {
                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                    }
                }

                @Override
                public void must(boolean isSuccess) {
                    mRefreshLayout.finishLoadMore(isSuccess);
                }
            });
        } else {
            if (mRemoteRepo.showNoDataHint()) {
                Common.showToast("没有更多数据啦");
            }
            mRefreshLayout.finishLoadMore();
        }
    }

    @Override
    protected void initData() {
        mRemoteRepo = (RemoteRepo<Response>) mModel.getBaseRepo();
        super.initData();
    }

    /**
     * FragmentR页面，调试过程中不需要每次都刷新，就调用这个方法来加载数据。只是为了方便测试
     */
    public void showDataBase() {
    }

    public void onResponse(Response response) {

    }

    @CallSuper
    @Override
    public void onAdapterPrepared() {
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
