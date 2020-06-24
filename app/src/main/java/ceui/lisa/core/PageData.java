package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.models.IllustsBean;

public class PageData {

    private String uuid = "";
    private List<IllustsBean> illustList = new ArrayList<>();

    public PageData(String uuid, List<IllustsBean> illustList) {
        this.uuid = uuid;
        this.illustList = illustList;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public List<IllustsBean> getIllustList() {
        return illustList;
    }

    public void setIllustList(List<IllustsBean> illustList) {
        this.illustList = illustList;
    }
}
