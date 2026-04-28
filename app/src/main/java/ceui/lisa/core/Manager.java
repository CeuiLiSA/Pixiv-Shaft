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
import ceui.pixiv.ui.task.TaskPool;
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

    /**
     * 恢复未完成的下载任务。每条记录的 taskGson 内嵌完整 IllustsBean (~80KB)，
     * 过去全表加载在主线程触发过 OOM (CursorWindow.nativeGetString)。改为：
     *   1) 后台线程执行，避免阻塞 UI 启动；
     *   2) 限制条数，并先 trim 历史堆积条目；
     *   3) 包一层 try/catch，OOM/DB 异常时静默跳过而不让启动崩溃。
     */
    private static final int MAX_RESTORE_ITEMS = 100;

    public void restore() {
        Schedulers.io().scheduleDirect(() -> {
            try {
                AppDatabase db = AppDatabase.getAppDatabase(mContext);
                db.downloadDao().trimDownloading(MAX_RESTORE_ITEMS);
                List<DownloadingEntity> downloadingEntities =
                        db.downloadDao().getRecentDownloading(MAX_RESTORE_ITEMS);
                if (Common.isEmpty(downloadingEntities)) {
                    return;
                }
                Common.showLog("downloadingEntities " + downloadingEntities.size());
                List<DownloadItem> restored = new ArrayList<>();
                for (DownloadingEntity entity : downloadingEntities) {
                    try {
                        DownloadItem downloadItem = Shaft.sGson.fromJson(entity.getTaskGson(), DownloadItem.class);
                        if (downloadItem != null) {
                            restored.add(downloadItem);
                        }
                    } catch (Exception ex) {
                        Common.showLog("Manager restore parse error: " + ex.getMessage());
                    }
                }
                synchronized (this) {
                    content = restored;
                }
                AndroidSchedulers.mainThread().scheduleDirect(() ->
                        Common.showToast("下载记录恢复成功"));
            } catch (Throwable t) {
                Common.showLog("Manager restore failed: " + t.getMessage());
            }
        });
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

            long t0 = System.nanoTime();
            boolean isTaskExist = false;
            for (DownloadItem item : content) {
                if (item.isSame(bean)) {
                    isTaskExist = true;
                }
            }
            long dedupMs = (System.nanoTime() - t0) / 1_000_000;

            if (!isTaskExist) {
                long t1 = System.nanoTime();
                safeAdd(bean);
                long safeAddMs = (System.nanoTime() - t1) / 1_000_000;
                Common.showLog("[PERF] addTask #" + content.size()
                        + " dedupMs=" + dedupMs + " safeAddMs=" + safeAddMs);
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

        long t0 = System.nanoTime();
        entity.setTaskGson(Shaft.sGson.toJson(item));
        long gsonMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownloading(entity);
        long dbMs = (System.nanoTime() - t1) / 1_000_000;

        Common.showLog("[PERF] safeAdd gsonMs=" + gsonMs + " dbInsertMs=" + dbMs);
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
            long t0 = System.nanoTime();
            for (DownloadItem item : list) {
                addTask(item);
            }
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            Common.showLog("[PERF] addTasks total=" + list.size()
                    + " items, totalMs=" + totalMs);
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
        Common.showLog("[DL-CACHE] downloadOne enter uuid=" + downloadItem.getUuid()
                + " name=" + downloadItem.getName() + " url=" + downloadItem.getUrl());
        // check network status, if setting don't download when mobile data, stop all task
        if(!DownloadLimitTypeUtil.canDownloadNow()){
            stopAll();
            return;
        }

        DownloadFileFactory factory;
        try {
            if (Shaft.sSettings.getDownloadWay() == 0 || downloadItem.getIllust().isGif()) {
                factory = new Android10DownloadFactory22(context, downloadItem);
            } else {
                factory = new SAFactory(context, downloadItem);
            }
        } catch (Exception e) {
            Common.showLog("[DL] factory init failed: " + e);
            e.printStackTrace();
            Common.showToast(mContext.getString(R.string.string_365));
            complete(downloadItem, false);
            // Halt the queue instead of looping: the most common cause is a lost
            // or revoked SAF root — every subsequent SAF item would fail the same
            // way and spam the user with duplicate toasts. Let them fix the
            // folder and re-start manually.
            stopAll();
            return;
        }
        currentIllustID = downloadItem.getIllust().getId();
        Common.showLog("Manager 下载单个 当前进度" + downloadItem.getNonius());
        uuid = downloadItem.getUuid();

        // Skip policy + existing file: facade wants us to skip the write entirely.
        boolean shouldSkip =
                (factory instanceof Android10DownloadFactory22 && ((Android10DownloadFactory22) factory).isSkip())
             || (factory instanceof SAFactory && ((SAFactory) factory).isSkip());
        if (shouldSkip) {
            Common.showLog("[DL] skip download (already exists), illust=" + downloadItem.getIllust().getId());
            complete(downloadItem, true);
            // Post to main handler instead of calling loop() directly — matches
            // the doFinally path (RxJava posts via observeOn). A direct call would
            // create synchronous recursion when many items are skipped in a row.
            AndroidSchedulers.mainThread().scheduleDirect(this::loop);
            return;
        }

        long fileSize = MediaStoreUtil.length(factory.query(), context);
        long passSize = (!downloadItem.shouldStartNewDownload() && fileSize >= 0) ? fileSize : 0;

        // 准备目标文件
        Uri targetUri;
        try {
            targetUri = factory.insert();
        } catch (Exception e) {
            Common.showLog("[DL] factory.insert() failed: " + e);
            e.printStackTrace();
            Common.showToast(mContext.getString(R.string.string_365));
            complete(downloadItem, false);
            // Same rationale as factory init failure above — typically a lost
            // SAF tree or full MediaStore. Stop the queue so we don't spam.
            stopAll();
            return;
        }
        if (targetUri == null) {
            Common.showLog("[DL] factory.insert() returned null targetUri");
            Common.showToast(mContext.getString(R.string.string_365));
            complete(downloadItem, false);
            stopAll();
            return;
        }

        // 若二级详情页已经通过 Glide 缓存了原图，直接复制，避免重复网络请求
        final String dlUrl = downloadItem.getUrl();
        final boolean isGif = downloadItem.getIllust().isGif();
        final File cachedFile;
        if (passSize != 0) {
            Common.showLog("[DL-CACHE] skip peek, passSize=" + passSize + " (resume), url=" + dlUrl);
            cachedFile = null;
        } else if (isGif) {
            Common.showLog("[DL-CACHE] skip peek, illust isGif, url=" + dlUrl);
            cachedFile = null;
        } else {
            File peeked = TaskPool.peekCachedFile(dlUrl);
            if (peeked != null) {
                Common.showLog("[DL-CACHE] HIT path=" + peeked.getAbsolutePath()
                        + " size=" + peeked.length() + " url=" + dlUrl);
                cachedFile = peeked;
            } else {
                Common.showLog("[DL-CACHE] MISS url=" + dlUrl);
                cachedFile = null;
            }
        }

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
                long contentLength;
                long copyStartNs = System.nanoTime();
                if (cachedFile != null) {
                    Common.showLog("[DL-CACHE] begin local copy, src=" + cachedFile.getAbsolutePath()
                            + " dst=" + targetUri);
                    inputStream = new java.io.FileInputStream(cachedFile);
                    contentLength = cachedFile.length();
                } else {
                    Common.showLog("[DL-CACHE] begin network fetch, url=" + dlUrl
                            + " passSize=" + passSize + " dst=" + targetUri);
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        Common.showLog("[DL-CACHE] network HTTP " + response.code() + " url=" + dlUrl);
                        emitter.onError(new IOException("HTTP " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        Common.showLog("[DL-CACHE] network empty body url=" + dlUrl);
                        emitter.onError(new IOException("Empty response body"));
                        return;
                    }
                    inputStream = body.byteStream();
                    contentLength = body.contentLength();
                }

                long totalSize = contentLength > 0 ? contentLength + passSize : 0;
                Common.showLog("[DL-CACHE] contentLength=" + contentLength + " totalSize=" + totalSize
                        + " source=" + (cachedFile != null ? "cache" : "network"));

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
                long elapsedMs = (System.nanoTime() - copyStartNs) / 1_000_000L;
                Common.showLog("[DL-CACHE] write done source=" + (cachedFile != null ? "cache" : "network")
                        + " bytes=" + downloaded + " elapsedMs=" + elapsedMs + " dst=" + targetUri);
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
                Common.showLog("[DL-CACHE] db inserted DownloadEntity fileName=" + downloadEntity.getFileName()
                        + " filePath=" + downloadEntity.getFilePath());
                //通知FragmentDownloadFinish 添加这一项
                Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                intent.putExtra(Params.CONTENT, downloadEntity);
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
            }

            // 通知相册下载完成
            // 由各 Backend 的 onFinish 决定具体策略：
            //   MediaStore (Q+) — 清除 IS_PENDING；
            //   旧版 File / SAF — 调用 MediaScannerConnection.scanFile；
            //   AppCache (ugoira zip 等中间产物) — 不通知。
            factory.finishWrite();

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
