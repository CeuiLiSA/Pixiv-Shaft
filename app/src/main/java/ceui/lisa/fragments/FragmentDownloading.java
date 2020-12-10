package ceui.lisa.fragments;

import android.content.IntentFilter;
import android.view.View;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.DownloadingAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyDownloadTaskBinding;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.notification.DownloadReceiver;
import ceui.lisa.utils.Params;

public class FragmentDownloading extends LocalListFragment<FragmentBaseListBinding, DownloadItem> {

    private DownloadReceiver<?> mReceiver;

    @Override
    public BaseAdapter<DownloadItem, RecyDownloadTaskBinding> adapter() {
        return new DownloadingAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<DownloadItem>>() {
            @Override
            public List<DownloadItem> first() {
                return Manager.get().getContent();
            }

            @Override
            public List<DownloadItem> next() {
                return null;
            }
        };
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void onAdapterPrepared() {
        super.onAdapterPrepared();
        IntentFilter intentFilter = new IntentFilter();
        mReceiver = new DownloadReceiver<>((Callback<Integer>) entity -> {
            int position = entity;
            if (position < allItems.size()) {
                allItems.remove(position);
                mAdapter.notifyItemRemoved(position);
                mAdapter.notifyItemRangeChanged(position, allItems.size() - position);
            }

            if (allItems.size() == 0) {
                emptyRela.setVisibility(View.VISIBLE);
            }
        }, DownloadReceiver.NOTIFY_FRAGMENT_DOWNLOADING);
        intentFilter.addAction(Params.DOWNLOAD_ING);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
        }
        Manager.get().setCallback(null);
    }
}
