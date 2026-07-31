package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import ceui.lisa.models.IllustsBean;

public class PageData implements IDWithList<IllustsBean>{

    private final String uuid;
    private String nextUrl;
    private final List<IllustsBean> illustList;
    private final AtomicBoolean loadingNextPage = new AtomicBoolean(false);

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

    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }

    /**
     * Acquires this page session's pagination gate.
     *
     * The gate belongs to PageData rather than an Activity so a configuration
     * change cannot start a duplicate request, while unrelated detail pages
     * remain independent.
     */
    public boolean tryStartNextPageLoad() {
        return loadingNextPage.compareAndSet(false, true);
    }

    public void finishNextPageLoad() {
        loadingNextPage.set(false);
    }
}
