package ceui.lisa.fragments;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.DownloadingAdapter;
import ceui.lisa.database.IllustTask;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyDownloadTaskBinding;
import ceui.lisa.download.TaskQueue;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class FragmentDownloading extends LocalListFragment<FragmentBaseListBinding,
        IllustTask> {

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
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        int position = (int) channel.getObject();
        Common.showLog(className + "删除第 " + position + "个");
        allItems.remove(position);
        mAdapter.notifyItemRemoved(position);
        mAdapter.notifyItemRangeChanged(position, allItems.size() - position);

        if (TaskQueue.get().getTasks().size() == 0) {
            autoRefresh();
        }
    }
}
