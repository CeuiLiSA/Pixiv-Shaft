package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ceui.lisa.models.IllustsBean;

public class PageData implements IDWithList<IllustsBean>{

    private final String uuid;
    private final String nextUrl;
    private final List<IllustsBean> illustList;

    public PageData(List<IllustsBean> illustList) {
        this.uuid = UUID.randomUUID().toString();
        this.nextUrl = null;
        this.illustList = new ArrayList<>(illustList);
    }

    public PageData(String uuid, String nextUrl, List<IllustsBean> illustList) {
        this.uuid = uuid;
        this.nextUrl = nextUrl;
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

    public String getNextUrl() {
        return nextUrl;
    }
}
