package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ceui.lisa.models.IllustsBean;

public class PageData implements IDWithList<IllustsBean>{

    private String uuid;
    private List<IllustsBean> illustList;

    public PageData(List<IllustsBean> illustList) {
        this.uuid = UUID.randomUUID().toString();
        this.illustList = new ArrayList<>(illustList);
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
