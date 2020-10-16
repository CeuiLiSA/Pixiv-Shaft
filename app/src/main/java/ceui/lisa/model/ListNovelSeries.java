package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.NovelSeriesItem;

public class ListNovelSeries implements ListShow<NovelSeriesItem> {

    private String next_url;
    private List<NovelSeriesItem> novel_series_details;

    @Override
    public List<NovelSeriesItem> getList() {
        return novel_series_details;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
