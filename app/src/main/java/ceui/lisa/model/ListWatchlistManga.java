package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.WatchlistMangaItem;

public class ListWatchlistManga implements ListShow<WatchlistMangaItem> {
    private String next_url;
    private List<WatchlistMangaItem> series;

    @Override
    public List<WatchlistMangaItem> getList() {
        return series;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
