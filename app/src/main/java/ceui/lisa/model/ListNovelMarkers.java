package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.MarkedNovelItem;

public class ListNovelMarkers implements ListShow<MarkedNovelItem> {
    private String next_url;
    private List<MarkedNovelItem> marked_novels;
    @Override
    public List<MarkedNovelItem> getList() {
        return marked_novels;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
