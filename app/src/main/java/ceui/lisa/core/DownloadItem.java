package ceui.lisa.core;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.UUID;

import ceui.lisa.download.FileCreator;
import ceui.lisa.file.FileName;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class DownloadItem implements Serializable {

    private String name;
    private String url;
    private String showUrl;
    private String uuid;
    private final IllustsBean illust;
    private int index;
    private boolean autoSave = true;
    private int state = DownloadState.INIT;
    private boolean paused = false;
    private int nonius = 0;

    public DownloadItem(IllustsBean illustsBean, int index) {
        this.illust = illustsBean;
        this.uuid = UUID.randomUUID().toString();
        if (this.illust.isGif()) {
            this.name = new FileName().zipName(illustsBean);
        } else {
            this.name = FileCreator.customFileName(illustsBean, index);
            Common.showLog("saasdadw 给DownloadItem " + this.name);
        }
        this.index = index;
        Common.showLog("随机生成一个UUID");
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getShowUrl() {
        return showUrl;
    }

    public void setShowUrl(String showUrl) {
        this.showUrl = showUrl;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public IllustsBean getIllust() {
        return illust;
    }

    public void setUrl(String url) {
        Common.showLog("DownloadItem 准备下载：" + url);
        this.url = url;
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    public void setAutoSave(boolean autoSave) {
        this.autoSave = autoSave;
    }

    public boolean isSame(DownloadItem next) {
        return next != null &&
                TextUtils.equals(name, next.name) &&
                TextUtils.equals(url, next.url);
    }

    public int getState() {
        if(this.paused){
            return DownloadState.PAUSED;
        }
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused(){
        return this.paused;
    }

    public int getNonius() {
        return nonius;
    }

    public void setNonius(int nonius) {
        this.nonius = nonius;
    }

    public boolean shouldStartNewDownload() {
        return this.state == DownloadState.INIT || this.state == DownloadState.FAILED;
    }

    public static class DownloadState {
        public static final int INIT = 0;
        public static final int DOWNLOADING = 1;
        public static final int SUCCESS = 2;
        public static final int FAILED = 3;
        public static final int PAUSED = 4;
    }
}
