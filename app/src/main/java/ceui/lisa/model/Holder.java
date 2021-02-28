package ceui.lisa.model;

import java.io.Serializable;

import ceui.lisa.core.DownloadItem;

public class Holder implements Serializable {

    private int code;
    private int index;
    private DownloadItem mDownloadItem;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public DownloadItem getDownloadItem() {
        return mDownloadItem;
    }

    public void setDownloadItem(DownloadItem downloadItem) {
        mDownloadItem = downloadItem;
    }
}
