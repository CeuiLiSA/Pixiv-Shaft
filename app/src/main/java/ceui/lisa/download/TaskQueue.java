package ceui.lisa.download;

import com.liulishuo.okdownload.DownloadTask;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;

import ceui.lisa.adapters.DownloadTaskAdapter;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class TaskQueue {

    private ArrayList<DownloadTask> allTasks = new ArrayList<>();
    private final QueueListener listener = new QueueListener();

    private TaskQueue(){
    }

    private static class SingletonHolder {
        private static TaskQueue instance = new TaskQueue();
    }

    public ArrayList<DownloadTask> getTasks(){
        return allTasks;
    }

    public static TaskQueue get() {
        return SingletonHolder.instance;
    }

    public void addTask(DownloadTask downloadTask){
        Common.showLog("TaskQueue addTask " + downloadTask.toString());
        allTasks.add(downloadTask);
    }

    public void removeTask(DownloadTask downloadTask){
        Common.showLog("TaskQueue removeTask " + downloadTask.toString());
        allTasks.remove(downloadTask);
        Channel channel = new Channel();
        channel.setReceiver("FragmentDownloading");
        EventBus.getDefault().post(channel);
    }

    public void clearTask(){
        allTasks.clear();
    }

    public void bind(DownloadTaskAdapter.TagHolder holder, int position){
        final DownloadTask task = allTasks.get(position);
        listener.bind(task, holder);
        listener.resetInfo(task, holder);
    }
}
