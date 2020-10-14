package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.MangaSeriesItem;

public class ListMangaSeries implements ListShow<MangaSeriesItem> {

    private String next_url;
    private List<MangaSeriesItem> illust_series_details;

    @Override
    public List<MangaSeriesItem> getList() {
        return illust_series_details;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
