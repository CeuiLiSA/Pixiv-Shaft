package ceui.lisa.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.databinding.ViewDataBinding;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;


import com.scwang.smart.refresh.footer.ClassicsFooter;
import com.scwang.smart.refresh.header.FalsifyFooter;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.SimpleUserAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.Starable;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.notification.CommonReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public abstract class ReceiverListFragment<Layout extends ViewDataBinding,
        Response extends ListShow<Item>, Item> extends NetListFragment<Layout, Response, Item> {

    protected BroadcastReceiver mReceiver = null, dataReceiver = null, scrollReceiver = null;

    @Override
    public void onAdapterPrepared() {
        super.onAdapterPrepared();
        //注册本地广播
        if (mAdapter instanceof IAdapter || mAdapter instanceof EventAdapter) {
            {
                IntentFilter intentFilter = new IntentFilter();
                mReceiver = new CommonReceiver((BaseAdapter<Starable, ?>) mAdapter);
                intentFilter.addAction(Params.LIKED_ILLUST);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
            }
            if (mAdapter instanceof IAdapter) {
                addPageLoadReceiver();
                addPageScrollReceiver();
            }
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

    private void addPageLoadReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        dataReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    //接受VActivity传过来的ListIllust 数据
                    PageData pageData = Container.get().getPage(uuid);
                    if (pageData != null) {
                        if (TextUtils.equals(pageData.getUUID(), uuid)) {
                            ListIllust listIllust = (ListIllust) bundle.getSerializable(Params.CONTENT);
                            if (listIllust != null){
                                if (!Common.isEmpty(listIllust.getList())) {
                                    if (!isAdded()) {
                                        return;
                                    }
                                    mResponse = (Response) listIllust;
                                    if (!Common.isEmpty(mResponse.getList())) {
                                        beforeNextLoad(mResponse.getList());
                                        mModel.load(mResponse.getList(), false);
                                        allItems = mModel.getContent();
                                        onNextLoaded(mResponse.getList());
                                        mAdapter.notifyItemRangeInserted(getStartSize(), mResponse.getList().size());
                                    }
                                    mRemoteRepo.setNextUrl(mResponse.getNextUrl());
                                    mAdapter.setNextUrl(mResponse.getNextUrl());
                                    if (!TextUtils.isEmpty(mResponse.getNextUrl())) {
                                        mRefreshLayout.setRefreshFooter(new ClassicsFooter(mContext));
                                    } else {
                                        mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.FRAGMENT_ADD_DATA);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(dataReceiver, intentFilter);
    }

    private void addPageScrollReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        scrollReceiver = new CallBackReceiver(new BaseReceiver.CallBack() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    int index = bundle.getInt(Params.INDEX);
                    String pageUUID = bundle.getString(Params.PAGE_UUID);
                    if (TextUtils.equals(pageUUID, uuid)) {
                        try {
                            mRecyclerView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mRecyclerView.smoothScrollToPosition(index + mAdapter.headerSize());
                                }
                            }, 200L);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        intentFilter.addAction(Params.FRAGMENT_SCROLL_TO_POSITION);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(scrollReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
        }
        if (dataReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(dataReceiver);
        }
        if (scrollReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(scrollReceiver);
        }
    }
}
