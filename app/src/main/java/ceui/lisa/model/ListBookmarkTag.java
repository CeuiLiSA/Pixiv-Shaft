package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.TagsBean;

public class ListBookmarkTag implements ListShow<TagsBean> {

    private BookmarkDetailBean bookmark_detail;
    private String next_url;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public BookmarkDetailBean getBookmark_detail() {
        return bookmark_detail;
    }

    public void setBookmark_detail(BookmarkDetailBean bookmark_detail) {
        this.bookmark_detail = bookmark_detail;
    }

    @Override
    public List<TagsBean> getList() {
        return bookmark_detail.tags;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }

    public static class BookmarkDetailBean {
        /**
         * is_bookmarked : false
         * tags : [{"name":"オリジナル","is_registered":false},{"name":"創作","is_registered":false},{"name":"女の子","is_registered":false},{"name":"擬人化","is_registered":false},{"name":"ストラップシューズ","is_registered":false},{"name":"チョコケーキ6姉妹","is_registered":false},{"name":"オリジナル10000users入り","is_registered":false},{"name":"procreate","is_registered":false},{"name":"らくがき","is_registered":false},{"name":"習作","is_registered":false},{"name":"我是特殊分类","is_registered":false},{"name":"VOCALOID","is_registered":false}]
         * restrict : public
         */

        private boolean is_bookmarked;
        private String restrict;
        private List<TagsBean> tags;

        public boolean isIs_bookmarked() {
            return is_bookmarked;
        }

        public void setIs_bookmarked(boolean is_bookmarked) {
            this.is_bookmarked = is_bookmarked;
        }

        public String getRestrict() {
            return restrict;
        }

        public void setRestrict(String restrict) {
            this.restrict = restrict;
        }

        public List<TagsBean> getTags() {
            return tags;
        }

        public void setTags(List<TagsBean> tags) {
            this.tags = tags;
        }
    }
}
