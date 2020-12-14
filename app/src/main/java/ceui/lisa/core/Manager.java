package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.DownloadingEntity;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import rxhttp.RxHttp;
import rxhttp.wrapper.entity.Progress;

public class Manager {

    private List<DownloadItem> content = new ArrayList<>();
    private Disposable handle = null;
    private long nonius;

    private boolean isRunning = false;

    private Manager() {
        nonius = 0L;
    }

    public void restore(Context context) {
        List<DownloadingEntity> downloadingEntities = AppDatabase.getAppDatabase(context).downloadDao().getAllDownloading();
        if (!Common.isEmpty(downloadingEntities)) {
            if (content != null) {
                content = new ArrayList<>();
            }
            for (DownloadingEntity entity : downloadingEntities) {
                DownloadItem downloadItem = Shaft.sGson.fromJson(entity.getTaskGson(), DownloadItem.class);
                content.add(downloadItem);
            }
            Common.showToast("下载记录恢复成功");
        }
    }

    public static Manager get() {
        return Manager.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final Manager INSTANCE = new Manager();
    }

    public void addTask(DownloadItem bean, Context context) {
        addTask(bean, context, true);
    }

    public void addTask(DownloadItem bean, Context context, boolean showToast) {
        if (content == null) {
            content = new ArrayList<>();
        }
        boolean isTaskExist = false;
        for (DownloadItem item : content) {
            if (item.isSame(bean)) {
                isTaskExist = true;
            }
        }
        if (!isTaskExist) {
            safeAdd(bean);
        }
        if (showToast) {
            String str = "当前" + content.size() + "个任务正在下载中";
            Common.showToast(str);
        }
        start(context);
    }

    private void safeAdd(DownloadItem item) {
        Common.showLog("Manager safeAdd " + item.getUuid());
        content.add(item);
        DownloadingEntity entity = new DownloadingEntity();
        entity.setUuid(item.getUuid());
        entity.setTaskGson(Shaft.sGson.toJson(item));
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownloading(entity);
    }

    private void safeDelete(DownloadItem item) {
        safeDelete(item, true);
    }

    private void safeDelete(DownloadItem item, boolean isDownloadSuccess) {
        content.remove(item);
        if (isDownloadSuccess) {
            DownloadingEntity entity = new DownloadingEntity();
            entity.setUuid(item.getUuid());
            entity.setTaskGson(Shaft.sGson.toJson(item));
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().deleteDownloading(entity);
        }
    }

    public void addTasks(List<DownloadItem> list, Context context) {
        if (!Common.isEmpty(list)) {
            for (DownloadItem item : list) {
                addTask(item, context, false);
            }
        }
    }

    public void start(Context context) {
        if (isRunning) {
            Common.showLog("Manager 正在下载中，不用多次start");
            return;
        }
        checkPipe(context);
    }

    private void checkPipe(Context context) {
        if (Common.isEmpty(content)) {
            isRunning = false;
            Common.showLog("Manager 已经全部下载完成");
            Common.showToast("全部下载完成");
            return;
        }
        isRunning = true;
        DownloadItem item = content.get(0);
        downloadOne(context, item);
    }

    private void downloadOne(Context context, DownloadItem bean) {
        Android10DownloadFactory factory = new Android10DownloadFactory(context, bean);
        Common.showLog("Manager 下载单个 当前进度" + nonius);
        uuid = bean.getUuid();
        handle = RxHttp.get(bean.getUrl())
                .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER)
                .setRangeHeader(nonius, true)
                .asDownload(factory, AndroidSchedulers.mainThread(), new Consumer<Progress>() {
                    @Override
                    public void accept(Progress progress) {
                        nonius = progress.getCurrentSize();
                        currentProgress = progress.getProgress();
                        Common.showLog("manager currentProgress " + currentProgress);
                        if (mCallback != null) {
                            mCallback.doSomething(progress);
                        }
                    }
                }) //指定主线程回调
                .subscribe(s -> {//s为String类型，这里为文件存储路径
                    Common.showLog("downloadOne " + s);
                    //下载完成，处理相关逻辑
                    currentProgress = 0;
                    nonius = 0L;

                    {
                        //通知 DOWNLOAD_ING 下载完成
                        Intent intent = new Intent(Params.DOWNLOAD_ING);
                        intent.putExtra(Params.INDEX, 0);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                    }

                    {
                        //通知 DOWNLOAD_FINISH 下载完成
                        DownloadEntity downloadEntity = new DownloadEntity();
                        downloadEntity.setIllustGson(Shaft.sGson.toJson(bean.getIllust()));
                        downloadEntity.setFileName(bean.getName());
                        downloadEntity.setDownloadTime(System.currentTimeMillis());
                        downloadEntity.setFilePath(factory.fileUri.toString());
                        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                        //通知FragmentDownloadFinish 添加这一项
                        Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                        intent.putExtra(Params.CONTENT, downloadEntity);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                    }

                    safeDelete(bean);
                    checkPipe(context);

//                    contentResolver.update(item, null, null, null);
                }, throwable -> {
                    //下载失败，处理相关逻辑
                    Common.showLog("下载失败 " + throwable.toString());
                    safeDelete(bean, false);
                    checkPipe(context);
                });
    }

    private int currentProgress;

    public int getCurrentProgress() {
        return currentProgress;
    }

    private String uuid;

    public String getUuid() {
        return uuid;
    }

    private Callback<Progress> mCallback;

    public Callback<Progress> getCallback() {
        return mCallback;
    }

    public void setCallback(Callback<Progress> callback) {
        mCallback = callback;
    }

    public List<DownloadItem> getContent() {
        return content;
    }

    public void stop() {
        isRunning = false;
        if (handle != null) {
            handle.dispose();
        }
        Shaft.sSettings.setCurrentProgress(nonius);
        Local.setSettings(Shaft.sSettings);
    }

    public void clear() {
        stop();
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().deleteAllDownloading();
        content.clear();
    }
}
