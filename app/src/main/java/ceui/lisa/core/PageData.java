package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.models.IllustsBean;

public class PageData implements IDWithList<IllustsBean>{

    private String uuid;
    private List<IllustsBean> illustList;

    public PageData(String uuid, List<IllustsBean> illustList) {
        this.uuid = uuid;
        this.illustList = illustList;
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    @Override
    public List<IllustsBean> getList() {
        return illustList;
    }
}
