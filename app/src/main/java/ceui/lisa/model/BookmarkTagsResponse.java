package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.BookmarkTagsBean;

public class BookmarkTagsResponse implements ListShow<BookmarkTagsBean> {

    /**
     * bookmark_tags : [{"name":"procreate","count":1},{"name":"らくがき","count":1},{"name":"習作","count":1}]
     * next_url : null
     */

    private String next_url;
    private List<BookmarkTagsBean> bookmark_tags;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public List<BookmarkTagsBean> getBookmark_tags() {
        return bookmark_tags;
    }

    public void setBookmark_tags(List<BookmarkTagsBean> bookmark_tags) {
        this.bookmark_tags = bookmark_tags;
    }

    @Override
    public List<BookmarkTagsBean> getList() {
        return bookmark_tags;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }

}
