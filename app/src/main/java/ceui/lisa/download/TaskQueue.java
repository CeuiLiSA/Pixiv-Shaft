package ceui.lisa.download;

import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
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

    public static TaskQueue get() {
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


                //通知FragmentNowDownload 删除已经下载完成的这一项
                Channel deleteChannel = new Channel();
                deleteChannel.setReceiver("FragmentDownloading");
                deleteChannel.setObject(i);
                EventBus.getDefault().post(deleteChannel);


                try {

                    DownloadEntity downloadEntity = new DownloadEntity();
                    downloadEntity.setFileName(tempTask.getDownloadTask().getFilename());
                    downloadEntity.setIllustGson(Shaft.sGson.toJson(tempTask.getIllustsBean()));
                    downloadEntity.setDownloadTime(System.currentTimeMillis());
                    downloadEntity.setFilePath(tempTask.getDownloadTask().getFile().getPath());
                    AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);

                    //通知FragmentHasDownload 添加这一项
                    Channel addChannel = new Channel();
                    addChannel.setReceiver("FragmentDownloadFinish");
                    addChannel.setObject(downloadEntity);
                    EventBus.getDefault().post(addChannel);

                    //通知FragmentSingleIllust 已下载完成
                    Channel channel22 = new Channel();
                    channel22.setReceiver("FragmentSingleIllust download finish");
                    channel22.setObject(tempTask.getIllustsBean().getId());
                    EventBus.getDefault().post(channel22);

                    new ImageSaver() {
                        @Override
                        File whichFile() {
                            return tempTask.getDownloadTask().getFile();
                        }
                    }.execute();

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

    private static class SingletonHolder {
        private static TaskQueue instance = new TaskQueue();
    }
}
