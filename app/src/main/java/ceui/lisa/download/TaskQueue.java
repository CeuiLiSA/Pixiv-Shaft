package ceui.lisa.download;

import com.liulishuo.okdownload.DownloadTask;

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
    private ArrayList<DownloadTask> realTask = new ArrayList<>();

    private TaskQueue() {
    }

    public static TaskQueue get() {
        return SingletonHolder.instance;
    }

    public ArrayList<IllustTask> getTasks() {
        return allTasks;
    }

    public void addTask(IllustTask downloadTask) {
        allTasks.add(downloadTask);
        realTask.add(downloadTask.getDownloadTask());
    }

    public void removeTask(IllustTask downloadTask, boolean isComplete) {
        for (int i = 0; i < allTasks.size(); i++) {
            if (allTasks.get(i).getDownloadTask() == downloadTask.getDownloadTask()) {
                Common.showLog("TaskQueue removeTask " + downloadTask.toString());

                final IllustTask tempTask = allTasks.get(i);

                allTasks.remove(i);
                realTask.remove(i);
                //无论是否下载成功，通知FragmentNowDownload 删除已经下载完成的这一项
                Channel deleteChannel = new Channel();
                deleteChannel.setReceiver("FragmentDownloading");
                deleteChannel.setObject(i);
                EventBus.getDefault().post(deleteChannel);


                if (isComplete) {
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

                    new ImageSaver() {
                        @Override
                        File whichFile() {
                            return tempTask.getDownloadTask().getFile();
                        }
                    }.execute();
                }
                break;
            }
        }
    }

    public void clearTask() {
        if (allTasks.size() != 0) {
            for (IllustTask allTask : allTasks) {
                File file = allTask.getDownloadTask().getFile();
                if (file.length() > 0) {
                    //删除下载到一半的作品
                    file.delete();
                }
            }
        }
        allTasks.clear();
        if (realTask != null) {
            DownloadTask[] strings = new DownloadTask[realTask.size()];
            realTask.toArray(strings);
            DownloadTask.cancel(strings);
        }
    }

    private static class SingletonHolder {
        private static TaskQueue instance = new TaskQueue();
    }
}
