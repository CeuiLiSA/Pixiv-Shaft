package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.DownloadingEntity;
import ceui.lisa.download.DownloadFileFactory;
import ceui.lisa.download.DownloadProgress;
import ceui.lisa.download.ImageSaver;
import ceui.lisa.download.MediaStoreUtil;
import ceui.lisa.helper.Android10DownloadFactory22;
import ceui.lisa.helper.SAFactory;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.Holder;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Manager {

    private final Context mContext = Shaft.getContext();
    private List<DownloadItem> content = new ArrayList<>();
    private Disposable handle = null;
    private boolean isRunning = false;

    private Manager() {
        uuid = "";
        currentIllustID = 0;
    }

    public void restore() {
        List<DownloadingEntity> downloadingEntities = AppDatabase.getAppDatabase(mContext).downloadDao().getAllDownloading();
        if (!Common.isEmpty(downloadingEntities)) {
            Common.showLog("downloadingEntities " + downloadingEntities.size());
            if (content != null) {
                content = new ArrayList<>();
            }
            for (DownloadingEntity entity : downloadingEntities) {
                try {
                    DownloadItem downloadItem = Shaft.sGson.fromJson(entity.getTaskGson(), DownloadItem.class);
                    content.add(downloadItem);
                } catch (Exception ex) {
                    Common.showLog("Manager restore error: " + ex.getMessage());
                }
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

    public void addTask(DownloadItem bean) {
        synchronized (this) {
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
            if(DownloadLimitTypeUtil.startTaskWhenCreate()){
                startAll();
            }
        }
    }

    private void safeAdd(DownloadItem item) {
        Common.showLog("Manager safeAdd " + item.getUuid());
        content.add(item);
        DownloadingEntity entity = new DownloadingEntity();
        entity.setFileName(item.getName());
        entity.setUuid(item.getUuid());
        entity.setTaskGson(Shaft.sGson.toJson(item));
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownloading(entity);
    }

    private void complete(DownloadItem item, boolean isDownloadSuccess) {
        if (isDownloadSuccess) {
            item.setState(DownloadItem.DownloadState.SUCCESS);
            setCallback(uuid, null);
            content.remove(item);

            DownloadingEntity entity = new DownloadingEntity();
            entity.setFileName(item.getName());
            entity.setUuid(item.getUuid());
            entity.setTaskGson(Shaft.sGson.toJson(item));
            AppDatabase.getAppDatabase(mContext).downloadDao().deleteDownloading(entity);
            if (Shaft.sSettings.isToastDownloadResult()) {
                Common.showToast(item.getName() + mContext.getString(R.string.has_been_downloaded));
            }
        } else {
            item.setNonius(0);
            item.setState(DownloadItem.DownloadState.FAILED);
        }
    }

    public void addTasks(List<DownloadItem> list) {
        if (!Common.isEmpty(list)) {
            for (DownloadItem item : list) {
                addTask(item);
            }
        }
    }

    public void startAll() {
        if (!Common.isEmpty(content)) {
            for (DownloadItem item : content) {
                //item.setProcessed(false);
                item.setPaused(false);
                if (item.getState() == DownloadItem.DownloadState.FAILED) {
                    item.setState(DownloadItem.DownloadState.INIT);
                }
            }
        }
        if (isRunning) {
            Common.showLog("Manager 正在下载中，不用多次start");
            return;
        }
        isRunning = true;
        loop();
    }

    public void startOne(String uuid) {
        for (int i = 0; i < content.size(); i++) {
            DownloadItem downloadItem = content.get(i);
            if (downloadItem != null && downloadItem.getUuid().equals(uuid)) {
                //downloadItem.setProcessed(false);
                downloadItem.setPaused(false);
                if(downloadItem.getState() == DownloadItem.DownloadState.FAILED){
                    downloadItem.setState(DownloadItem.DownloadState.INIT);
                }
                Common.showLog("已开始 " + uuid);
                break;
            }
        }

        if (isRunning) {
            Common.showLog("Manager 正在下载中，不用多次start");
            return;
        }
        isRunning = true;
        loop();
    }

    public void stopAll() {
        for (DownloadItem item : getContent()) {
            item.setPaused(true);
        }
        isRunning = false;
        if (handle != null) {
            handle.dispose();
        }
        Common.showLog("已经停止");
    }

    public void stopOne(String uuid){
        for (DownloadItem item : getContent()) {
            if(item.getUuid().equals(uuid)){
                item.setPaused(true);
                Common.showLog("已暂停 " + uuid);
                break;
            }
        }
        if(this.uuid.equals(uuid) && handle != null){
            handle.dispose();
        }
    }

    public void clearAll() {
        stopAll();
        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllDownloading();
        content.clear();
    }

    public void clearOne(String uuid) {
        stopOne(uuid);
        Optional<DownloadItem> item = content.stream().filter(it -> it.getUuid().equals(uuid)).findFirst();
        if (item.isPresent()) {
            DownloadItem downloadItem = item.get();
            DownloadingEntity entity = new DownloadingEntity();
            entity.setFileName(downloadItem.getName());
            entity.setUuid(downloadItem.getUuid());
            entity.setTaskGson(Shaft.sGson.toJson(downloadItem));
            AppDatabase.getAppDatabase(mContext).downloadDao().deleteDownloading(entity);
            content.remove(downloadItem);
        }
    }

    private void loop() {
        if (Common.isEmpty(content.stream().filter(it->!it.isPaused()).collect(Collectors.toList()))) {
            isRunning = false;
            Common.showLog("Manager 已经全部下载完成");
            return;
        }
        if(!isRunning){
            return;
        }

        DownloadItem item = getFirstOne();
        if (item != null) {
            downloadOne(mContext, item);
        } else {
            stopAll();
        }
    }

    private DownloadItem getFirstOne() {
        for (int i = 0; i < content.size(); i++) {
            DownloadItem downloadItem = content.get(i);
            if (downloadItem.getState() == DownloadItem.DownloadState.INIT || downloadItem.getState() == DownloadItem.DownloadState.DOWNLOADING) {
                return downloadItem;
            }
        }
        return null;
    }

    private void downloadOne(Context context, DownloadItem downloadItem) {
        // check network status, if setting don't download when mobile data, stop all task
        if(!DownloadLimitTypeUtil.canDownloadNow()){
            stopAll();
            return;
        }

        DownloadFileFactory factory;
        if (Shaft.sSettings.getDownloadWay() == 0 || downloadItem.getIllust().isGif()) {
            factory = new Android10DownloadFactory22(context, downloadItem);
        } else {
            factory = new SAFactory(context, downloadItem);
        }
        currentIllustID = downloadItem.getIllust().getId();
        Common.showLog("Manager 下载单个 当前进度" + downloadItem.getNonius());
        uuid = downloadItem.getUuid();
        long fileSize = MediaStoreUtil.length(factory.query(), context);
        long passSize = (!downloadItem.shouldStartNewDownload() && fileSize >= 0) ? fileSize : 0;

        // 准备目标文件
        Uri targetUri = factory.insert();

        OkHttpClient client = ((Shaft) Shaft.getContext()).getOkHttpClient();
        Request.Builder reqBuilder = new Request.Builder()
                .url(downloadItem.getUrl())
                .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER);
        if (passSize > 0) {
            reqBuilder.addHeader("Range", "bytes=" + passSize + "-");
        }
        Request request = reqBuilder.build();

        handle = io.reactivex.rxjava3.core.Observable.<String>create(emitter -> {
            Response response = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            try {
                response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    emitter.onError(new IOException("HTTP " + response.code()));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    emitter.onError(new IOException("Empty response body"));
                    return;
                }

                long contentLength = body.contentLength();
                long totalSize = contentLength > 0 ? contentLength + passSize : 0;

                inputStream = body.byteStream();
                if ("file".equals(targetUri.getScheme())) {
                    String path = targetUri.getPath();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(path, passSize > 0);
                    outputStream = fos;
                } else {
                    outputStream = context.getContentResolver().openOutputStream(targetUri, passSize > 0 ? "wa" : "w");
                }
                if (outputStream == null) {
                    emitter.onError(new IOException("Cannot open output stream for " + targetUri));
                    return;
                }

                byte[] buffer = new byte[8192];
                long downloaded = passSize;
                int lastProgress = 0;
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    if (emitter.isDisposed()) {
                        return;
                    }
                    outputStream.write(buffer, 0, len);
                    downloaded += len;
                    if (totalSize > 0) {
                        int progress = (int) (downloaded * 100 / totalSize);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            long finalDownloaded = downloaded;
                            long finalTotal = totalSize;
                            AndroidSchedulers.mainThread().scheduleDirect(() -> {
                                DownloadProgress dp = new DownloadProgress(progress, finalDownloaded, finalTotal);
                                downloadItem.setNonius(progress);
                                downloadItem.setCurrentSize(finalDownloaded);
                                downloadItem.setTotalSize(finalTotal);
                                downloadItem.setState(DownloadItem.DownloadState.DOWNLOADING);
                                Common.showLog("currentProgress " + progress);
                                try {
                                    Callback<DownloadProgress> c = getCallback(uuid);
                                    if (c != null) {
                                        c.doSomething(dp);
                                    }
                                } catch (Exception e) {
                                    Common.showLog("Manager progress callback error: " + e.getMessage());
                                }
                            });
                        }
                    }
                }
                outputStream.flush();
                emitter.onNext(targetUri.toString());
                emitter.onComplete();
            } catch (Exception e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            } finally {
                if (inputStream != null) try { inputStream.close(); } catch (Exception ignored) {}
                if (outputStream != null) try { outputStream.close(); } catch (Exception ignored) {}
                if (response != null) try { response.close(); } catch (Exception ignored) {}
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .doFinally(() -> {
            //下载完成，处理相关逻辑
            currentIllustID = 0;
            loop();
            Common.showLog("doFinally ");
        })
        .subscribe(s -> {
            //s为String类型，这里为文件存储路径
            Common.showLog("downloadOne " + s);

            if(downloadItem.getIllust().isGif()){
                Shaft.getMMKV().encode(Params.ILLUST_ID + "_" + downloadItem.getIllust().getId(), true);
                PixivOperate.unzipAndPlay(context, downloadItem.getIllust(), downloadItem.isAutoSave());
            }

            //通知 DOWNLOAD_ING 下载完成
            {
                Intent intent = new Intent(Params.DOWNLOAD_ING);
                Holder holder = new Holder();
                holder.setCode(Params.DOWNLOAD_SUCCESS);
                holder.setIndex(content.indexOf(downloadItem));
                holder.setDownloadItem(downloadItem);
                intent.putExtra(Params.CONTENT, holder);
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
            }

            //通知 DOWNLOAD_FINISH 下载完成
            {
                DownloadEntity downloadEntity = new DownloadEntity();
                downloadEntity.setIllustGson(Shaft.sGson.toJson(downloadItem.getIllust()));
                downloadEntity.setFileName(downloadItem.getName());
                downloadEntity.setDownloadTime(System.currentTimeMillis());
                downloadEntity.setFilePath(factory.getFileUri().toString());
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(downloadEntity);
                //通知FragmentDownloadFinish 添加这一项
                Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                intent.putExtra(Params.CONTENT, downloadEntity);
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
            }

            // 通知相册下载完成
            new ImageSaver(){
                @Override
                public File whichFile() {
                    Uri uri = factory.query();
                    if (uri == null || uri.getPath() == null) {
                        return null;
                    }
                    return new File(uri.getPath());
                }
            }.execute();

            complete(downloadItem, true);
        }, throwable -> {
            //下载失败，处理相关逻辑
            Common.showLog("Manager download error: " + throwable.getMessage());
            if (Shaft.sSettings.isToastDownloadResult()) {
                Common.showToast("下载失败，原因：" + throwable.toString());
            }
            Common.showLog("下载失败，原因：" + throwable.toString());
            complete(downloadItem, false);
            {
                //通知 DOWNLOAD_ING 有一项下载失败
                Intent intent = new Intent(Params.DOWNLOAD_ING);
                Holder holder = new Holder();
                holder.setCode(Params.DOWNLOAD_FAILED);
                holder.setIndex(content.indexOf(downloadItem));
                holder.setDownloadItem(downloadItem);
                intent.putExtra(Params.CONTENT, holder);
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
            }
        });
    }

    private String uuid;
    private int currentIllustID;

    public int getCurrentIllustID() {
        return currentIllustID;
    }

    public String getUuid() {
        return uuid;
    }

    private final Map<String, Callback<DownloadProgress>> mCallback = new HashMap<>();

    public Callback<DownloadProgress> getCallback(String uuid) {
        return mCallback.getOrDefault(uuid, null);
    }

    public void clearCallback() {
        mCallback.clear();
    }

    public void setCallback(Callback<DownloadProgress> callback) {
        mCallback.put("", callback);
    }

    public void setCallback(String uuid, Callback<DownloadProgress> callback) {
        mCallback.put(uuid, callback);
    }

    public List<DownloadItem> getContent() {
        return content;
    }
}
