package ceui.lisa.database;


import ceui.lisa.core.DownloadItem;
import ceui.lisa.models.IllustsBean;

public class IllustTask {

    private DownloadItem mDownloadTask;
    private IllustsBean mIllustsBean;

    public DownloadItem getDownloadTask() {
        return mDownloadTask;
    }

    public void setDownloadTask(DownloadItem downloadTask) {
        mDownloadTask = downloadTask;
    }

    public IllustsBean getIllustsBean() {
        return mIllustsBean;
    }

    public void setIllustsBean(IllustsBean illustsBean) {
        mIllustsBean = illustsBean;
    }

}
