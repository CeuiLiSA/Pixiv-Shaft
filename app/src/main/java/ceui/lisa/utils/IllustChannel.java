package ceui.lisa.utils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.model.IllustsBean;

public class IllustChannel {

    private IllustChannel() {
    }

    private List<IllustsBean> illustList = new ArrayList<>();

    private List<IllustsBean> downloadList = new ArrayList<>();

    private volatile static IllustChannel instance = null;

    public static IllustChannel get() {
        if (instance == null) {
            synchronized (IllustChannel.class) {
                if (instance == null) {
                    instance = new IllustChannel();
                }
            }
        }

        return instance;
    }

    public List<IllustsBean> getIllustList() {
        return illustList;
    }

    public void setIllustList(List<IllustsBean> illustList) {
        this.illustList = illustList;
    }

    public List<IllustsBean> getDownloadList() {
        return downloadList;
    }

    public void setDownloadList(List<IllustsBean> illustList) {
        this.downloadList = illustList;
    }
}
