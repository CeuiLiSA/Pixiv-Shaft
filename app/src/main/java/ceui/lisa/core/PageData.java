package ceui.lisa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import ceui.loxia.Illust;

public class PageData implements IDWithList<Illust>{

    private final String uuid;
    private String nextUrl;
    private final List<Illust> illustList;
    private final AtomicBoolean loadingNextPage = new AtomicBoolean(false);

    public PageData(List<Illust> illustList) {
        this.uuid = UUID.randomUUID().toString();
        this.nextUrl = null;
        this.illustList = new ArrayList<>(illustList);
    }

    public PageData(String uuid, String nextUrl, List<Illust> illustList) {
        this.uuid = uuid;
        this.nextUrl = nextUrl;
        this.illustList = new ArrayList<>(illustList);
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    @Override
    public List<Illust> getList() {
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
