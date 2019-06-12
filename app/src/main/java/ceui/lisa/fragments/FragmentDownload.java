package ceui.lisa.fragments;

import com.liulishuo.okdownload.DownloadTask;

import java.util.List;

import ceui.lisa.adapters.DownloadTaskAdapter;
import ceui.lisa.download.TaskQueue;

public class FragmentDownload extends BaseDataFragment<DownloadTaskAdapter, DownloadTask> {

    @Override
    List<DownloadTask> initList() {
        return TaskQueue.get().getTasks();
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initAdapter() {
        mAdapter = new DownloadTaskAdapter(allItems, mContext);
    }
}
