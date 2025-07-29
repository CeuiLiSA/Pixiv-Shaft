package ceui.lisa.core;


import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadingEntity;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DownloadLimitTypeUtil;

public class Manager {

    private final Context mContext = Shaft.getContext();
    private final String uuid;
    private final int currentIllustID;
    private List<DownloadItem> content = new ArrayList<>();
    private boolean isRunning = false;

    private Manager() {
        uuid = "";
        currentIllustID = 0;
    }

    public static Manager get() {
        return Manager.SingletonHolder.INSTANCE;
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
                    ex.printStackTrace();
                }
            }
            Common.showToast("下载记录恢复成功");
        }
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
            if (DownloadLimitTypeUtil.startTaskWhenCreate()) {
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
                if (downloadItem.getState() == DownloadItem.DownloadState.FAILED) {
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
        Common.showLog("已经停止");
    }

    public void stopOne(String uuid) {
        for (DownloadItem item : getContent()) {
            if (item.getUuid().equals(uuid)) {
                item.setPaused(true);
                Common.showLog("已暂停 " + uuid);
                break;
            }
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
        if (Common.isEmpty(content.stream().filter(it -> !it.isPaused()).collect(Collectors.toList()))) {
            isRunning = false;
            Common.showLog("Manager 已经全部下载完成");
            return;
        }
        if (!isRunning) {
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

    }

    public int getCurrentIllustID() {
        return currentIllustID;
    }

    public String getUuid() {
        return uuid;
    }

    public List<DownloadItem> getContent() {
        return content;
    }

    private static class SingletonHolder {
        private static final Manager INSTANCE = new Manager();
    }
}
