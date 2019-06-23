package ceui.lisa.database;

import com.liulishuo.okdownload.DownloadTask;

import ceui.lisa.model.IllustsBean;

public class IllustTask {

    private DownloadTask mDownloadTask;

    public DownloadTask getDownloadTask() {
        return mDownloadTask;
    }

    public void setDownloadTask(DownloadTask downloadTask) {
        mDownloadTask = downloadTask;
    }

    private IllustsBean mIllustsBean;

    public IllustsBean getIllustsBean() {
        return mIllustsBean;
    }

    public void setIllustsBean(IllustsBean illustsBean) {
        mIllustsBean = illustsBean;
    }

}
