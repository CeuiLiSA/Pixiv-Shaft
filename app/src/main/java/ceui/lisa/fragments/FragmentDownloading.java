package ceui.lisa.fragments;

import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.DownloadingAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.database.IllustTask;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyDownloadTaskBinding;
import ceui.lisa.download.TaskQueue;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.notification.DownloadReceiver;
import ceui.lisa.utils.Params;

public class FragmentDownloading extends LocalListFragment<FragmentBaseListBinding, IllustTask> {

    private DownloadReceiver<?> mReceiver;

    @Override
    public BaseAdapter<IllustTask, RecyDownloadTaskBinding> adapter() {
        return new DownloadingAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<IllustTask>>() {
            @Override
            public List<IllustTask> first() {
                return TaskQueue.get().getTasks();
            }

            @Override
            public List<IllustTask> next() {
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


            if (TaskQueue.get().getTasks().size() == 0) {
                mRefreshLayout.autoRefresh();
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
    }
}
