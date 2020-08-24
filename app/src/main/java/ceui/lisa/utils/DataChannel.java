package ceui.lisa.utils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.models.NovelBean;
import ceui.lisa.models.IllustsBean;

public class DataChannel {

    private volatile static DataChannel instance = null;

    private List<IllustsBean> downloadList = new ArrayList<>();

    private DataChannel() {
    }

    public static DataChannel get() {
        if (instance == null) {
            synchronized (DataChannel.class) {
                if (instance == null) {
                    instance = new DataChannel();
                }
            }
        }

        return instance;
    }

    public List<IllustsBean> getDownloadList() {
        return downloadList;
    }

    public void setDownloadList(List<IllustsBean> illustList) {
        this.downloadList = illustList;
    }
}
