package ceui.lisa.download;

import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.IllustTask;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class TaskQueue {

    private ArrayList<IllustTask> allTasks = new ArrayList<>();

    private TaskQueue() {
    }

    private static class SingletonHolder {
        private static TaskQueue instance = new TaskQueue();
    }

    public ArrayList<IllustTask> getTasks() {
        return allTasks;
    }

    public static TaskQueue get() {
        return SingletonHolder.instance;
    }

    public void addTask(IllustTask downloadTask) {
        Common.showLog("TaskQueue addTask " + downloadTask.toString());
        allTasks.add(downloadTask);
    }

    public void removeTask(IllustTask downloadTask) {
        for (int i = 0; i < allTasks.size(); i++) {
            if (allTasks.get(i).getDownloadTask() == downloadTask.getDownloadTask()) {
                Common.showLog("TaskQueue removeTask " + downloadTask.toString());

                Channel channel = new Channel();
                channel.setReceiver("FragmentDownload");
                channel.setObject(i);
                EventBus.getDefault().post(channel);


                try {
                    DownloadEntity downloadEntity = new DownloadEntity();
                    downloadEntity.setFileName(allTasks.get(i).getDownloadTask().getFilename());
                    Gson gson = new Gson();
                    downloadEntity.setIllustGson(gson.toJson(allTasks.get(i).getIllustsBean()));
                    downloadEntity.setDownloadTime(System.currentTimeMillis());
                    AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                    allTasks.remove(i);
                    break;

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void clearTask() {
        allTasks.clear();
    }
}
