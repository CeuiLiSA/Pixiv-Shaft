package ceui.lisa.http;

import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.IllustTask;
import ceui.lisa.download.ImageSaver;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import okhttp3.Interceptor;
import okhttp3.Request;

public class RequestQueue {

    private ArrayList<Interceptor.Chain> allTasks = new ArrayList<>();

    private RequestQueue() {
    }

    private static class SingletonHolder {
        private static RequestQueue instance = new RequestQueue();
    }

    public ArrayList<Interceptor.Chain> getTasks() {
        return allTasks;
    }

    public static RequestQueue get() {
        return SingletonHolder.instance;
    }

    public void addTask(Interceptor.Chain downloadTask) {
        Common.showLog("RequestQueue addTask " + downloadTask.toString());
        allTasks.add(downloadTask);
    }

    public void removeTask(Interceptor.Chain downloadTask) {
        for (int i = 0; i < allTasks.size(); i++) {
            if (allTasks.get(i) == downloadTask) {
                allTasks.remove(i);
            }
        }
    }

    public void clearTask() throws Exception {
        Common.showLog("RequestQueue clear ");
        String acsToken = Local.getUser().getResponse().getAccess_token();
        for (int i = 0; i < allTasks.size(); i++) {
            Request newRequest = allTasks.get(i).request()
                    .newBuilder()
                    .header("Authorization", acsToken)
                    .build();
            allTasks.get(i).proceed(newRequest);
        }

        allTasks.clear();
    }
}
