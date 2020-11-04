package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.download.ImageSaver;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.functions.Consumer;
import rxhttp.RxHttp;
import rxhttp.wrapper.entity.Progress;

public class Manager {

    private List<DownloadItem> content = new ArrayList<>();

    private boolean isRunning = false;

    private Manager() {
    }

    public static Manager get() {
        return Manager.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final Manager INSTANCE = new Manager();
    }

    public void addTask(DownloadItem bean) {
        content.add(bean);
    }

    public void addTasks(List<DownloadItem> list) {
        if (!Common.isEmpty(list)) {
            if (content == null) {
                content = new ArrayList<>();
            }
            content.addAll(list);
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
        downloadOne(item, context);
    }

    private void downloadOne(DownloadItem bean, Context context) {
        Common.showLog("Manager 下载单个 ");
        uuid = bean.getUuid();
        Uri item = bean.getUri();
        RxHttp.get(bean.getUrl())
                .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER)
                .asDownload(context, item, AndroidSchedulers.mainThread(), new Consumer<Progress>() {
                    @Override
                    public void accept(Progress progress) {
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

                    {
                        //通知
                        Common.showLog("通知 DOWNLOAD_ING 下载完成");
                        Intent intent = new Intent(Params.DOWNLOAD_ING);
                        intent.putExtra(Params.INDEX, 0);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                    }

                    {
                        //通知
                        Common.showLog("通知 DOWNLOAD_FINISH 下载完成");
                        DownloadEntity downloadEntity = new DownloadEntity();
                        downloadEntity.setIllustGson(Shaft.sGson.toJson(bean.getIllust()));
                        downloadEntity.setFileName(bean.getName());
                        downloadEntity.setDownloadTime(System.currentTimeMillis());
                        downloadEntity.setFilePath(item.toString());
                        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                        //通知FragmentDownloadFinish 添加这一项
                        Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                        intent.putExtra(Params.CONTENT, downloadEntity);
                        LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                    }


                    content.remove(bean);
                    checkPipe(context);

//                    contentResolver.update(item, null, null, null);
                }, throwable -> {
                    //下载失败，处理相关逻辑
                    Common.showLog(throwable.toString());
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

    public void setContent(List<DownloadItem> content) {
        this.content = content;
    }
}
