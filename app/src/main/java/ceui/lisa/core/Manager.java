package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.blankj.utilcode.util.ZipUtils;
import com.tencent.mmkv.MMKV;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.DownloadingEntity;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.ImageSaver;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
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
        item.setProcessed(true);
        if (isDownloadSuccess) {
            DownloadingEntity entity = new DownloadingEntity();
            entity.setUuid(item.getUuid());
            entity.setTaskGson(Shaft.sGson.toJson(item));
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().deleteDownloading(entity);
            content.remove(item);
        }
    }

    public void addTasks(List<DownloadItem> list, Context context) {
        if (!Common.isEmpty(list)) {
            for (DownloadItem item : list) {
                addTask(item, context);
            }
        }
    }

    public void start(Context context) {
        if (!Common.isEmpty(content)) {
            for (DownloadItem item : content) {
                item.setProcessed(false);
            }
        }
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
            return;
        }
        isRunning = true;
        DownloadItem item = getFirstOne();
        if (item != null) {
            downloadOne(context, item);
        } else {
            isRunning = false;
        }
    }

    private DownloadItem getFirstOne() {
        for (int i = 0; i < content.size(); i++) {
            if (!content.get(i).isProcessed()) {
                return content.get(i);
            }
        }
        return null;
    }

    private void downloadOne(Context context, DownloadItem bean) {
        final Uri downloadUri;
        final File downloadFile;
        if (bean.getIllust().isGif()) {
            File file = SAFile.createZipFile(context, bean.getName());
            downloadUri = Uri.fromFile(file);
            downloadFile = null;
        } else {
            if (Common.isAndroidQ()) {
                DocumentFile file = SAFile.getDocument(context, bean.getIllust(), bean.getIndex());
                if (file != null) {
                    downloadUri = file.getUri();
                } else {
                    downloadUri = null;
                }
                downloadFile = null;
            } else {
                downloadFile = FileCreator.createIllustFile(bean.getIllust(), bean.getIndex());
                downloadUri = Uri.fromFile(downloadFile);
            }
        }

        if (downloadUri != null) {
            currentIllustID = bean.getIllust().getId();
            Common.showLog("Manager 下载单个 当前进度" + nonius);
            uuid = bean.getUuid();
            handle = RxHttp.get(bean.getUrl())
                    .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER)
                    .setRangeHeader(nonius, true)
                    .asDownload(context, downloadUri, AndroidSchedulers.mainThread(), new Consumer<Progress>() {
                        @Override
                        public void accept(Progress progress) {
                            nonius = progress.getCurrentSize();
                            currentProgress = progress.getProgress();
                            try {
                                if (mCallback != null) {
                                    mCallback.doSomething(progress);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }) //指定主线程回调
                    .subscribe(s -> {//s为String类型，这里为文件存储路径
                        Common.showLog("downloadOne " + s);
                        //下载完成，处理相关逻辑
                        currentProgress = 0;
                        nonius = 0L;

                        if(bean.getIllust().isGif()){
                            MMKV.defaultMMKV().encode(Params.ILLUST_ID + "_" + bean.getIllust().getId(), true);
                            PixivOperate.unzipAndePlay(context, bean.getIllust());
                        }

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
                            downloadEntity.setFilePath(downloadUri.toString());
                            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                            //通知FragmentDownloadFinish 添加这一项
                            Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                            intent.putExtra(Params.CONTENT, downloadEntity);
                            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                        }

                        {
                            //通知相册刷新图片
                            if (downloadFile != null) {
                                new ImageSaver() {
                                    @Override
                                    public File whichFile() {
                                        return downloadFile;
                                    }
                                }.execute();
                            }
                        }

                        safeDelete(bean);
                        checkPipe(context);

                    }, throwable -> {
                        //下载失败，处理相关逻辑
                        Common.showLog("下载失败 " + throwable.toString());
                        safeDelete(bean, false);
                        checkPipe(context);
                    });
        } else {
            safeDelete(bean, false);
            checkPipe(context);
        }
    }

    private int currentProgress;

    public int getCurrentProgress() {
        return currentProgress;
    }

    private String uuid;
    private int currentIllustID;

    public int getCurrentIllustID() {
        return currentIllustID;
    }

    public void setCurrentIllustID(int currentIllustID) {
        this.currentIllustID = currentIllustID;
    }

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
