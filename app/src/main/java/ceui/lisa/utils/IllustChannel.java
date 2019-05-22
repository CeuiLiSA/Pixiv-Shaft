package ceui.lisa.utils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.response.IllustsBean;

public class IllustChannel {

    private IllustChannel() {
    }

    private List<IllustsBean> illustList = new ArrayList<>();
    private volatile static IllustChannel instance = null;

    public static IllustChannel get() {
        if (instance == null) {
            synchronized (IllustChannel.class) {
                if (instance == null) {
                    instance = new IllustChannel();
                    Common.showToast("新建了IllustChannel实例");
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
}
