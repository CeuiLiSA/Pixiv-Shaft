package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.WatchlistNovelItem;

public class ListWatchlistNovel implements ListShow<WatchlistNovelItem> {
    private String next_url;
    private List<WatchlistNovelItem> series;

    @Override
    public List<WatchlistNovelItem> getList() {
        return series;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
