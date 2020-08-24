package ceui.lisa.download;

import com.google.gson.Gson;


import java.io.File;
import java.util.ArrayList;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.IllustTask;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class GifQueue {

    private ArrayList<IllustTask> allTasks = new ArrayList<>();

    private GifQueue() {
    }

    public static GifQueue get() {
        return SingletonHolder.instance;
    }

    public ArrayList<IllustTask> getTasks() {
        return allTasks;
    }

    public void addTask(IllustTask downloadTask) {
        Common.showLog("TaskQueue addTask " + downloadTask.toString());
        allTasks.add(downloadTask);
    }

    public void removeTask(IllustTask downloadTask) {
        for (int i = 0; i < allTasks.size(); i++) {
            if (allTasks.get(i).getDownloadTask() == downloadTask.getDownloadTask()) {
                Common.showLog("TaskQueue removeTask " + downloadTask.toString());

                final IllustTask tempTask = allTasks.get(i);
                allTasks.remove(i);
                break;
            }
        }
    }

    public void clearTask() {
        allTasks.clear();
    }

    private static class SingletonHolder {
        private static GifQueue instance = new GifQueue();
    }
}
