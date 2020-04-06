package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.TagsBean;

public class ListTag implements ListShow<TagsBean> {

    /**
     * bookmark_tags : [{"name":"procreate","count":1},{"name":"らくがき","count":1},{"name":"習作","count":1}]
     * next_url : null
     */

    private String next_url;
    private List<TagsBean> bookmark_tags;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public List<TagsBean> getBookmark_tags() {
        return bookmark_tags;
    }

    public void setBookmark_tags(List<TagsBean> bookmark_tags) {
        this.bookmark_tags = bookmark_tags;
    }

    @Override
    public List<TagsBean> getList() {
        return bookmark_tags;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }

}
