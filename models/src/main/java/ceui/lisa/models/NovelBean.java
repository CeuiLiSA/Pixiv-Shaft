package ceui.lisa.models;

import java.io.Serializable;
import java.util.List;

public class NovelBean implements Serializable, Starable {


    /**
     * id : 11968607
     * title : 新任教師の成幸　―1日目・続続続―
     * caption : 日曜日に検定を3つも受ける羽目になって最悪な気持ちです。
     * restrict : 0
     * x_restrict : 0
     * is_original : false
     * image_urls : {"square_medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_11_128x128.jpg","medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_11_176mw.jpg","large":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_11_240mw.jpg"}
     * create_date : 2019-11-18T10:42:15+09:00
     * tags : [{"name":"ぼくたちは勉強ができない","translated_name":"我们真的学不来","added_by_uploaded_user":true},{"name":"なりふみ","translated_name":null,"added_by_uploaded_user":true},{"name":"古橋文乃","translated_name":"古桥文乃","added_by_uploaded_user":true},{"name":"唯我成幸","translated_name":null,"added_by_uploaded_user":true},{"name":"桐須真冬","translated_name":"桐须真冬","added_by_uploaded_user":true}]
     * page_count : 1
     * text_length : 2043
     * user : {"id":17725925,"name":"beco0893","account":"gradually-individual","profile_image_urls":{"medium":"https://s.pximg.net/common/images/no_profile.png"},"is_followed":false}
     * series : {}
     * is_bookmarked : false
     * total_bookmarks : 0
     * total_view : 0
     * visible : true
     * total_comments : 0
     * is_muted : false
     * is_mypixiv_only : false
     * is_x_restricted : false
     */

    private int id;
    private String title;
    private String coverUrl;
    private String caption;
    private int restrict;
    private int x_restrict;
    private boolean is_original;
    private boolean viewable;
    private ImageUrlsBean image_urls;
    private String create_date;
    private String contentOrder;
    private int page_count;
    private int text_length;
    private UserBean user;
    private SeriesBean series;
    private boolean is_bookmarked;
    private int total_bookmarks;
    private int total_view;
    private boolean visible;
    private boolean isLocalSaved;
    private int total_comments;
    private boolean is_muted;
    private boolean is_mypixiv_only;
    private boolean is_x_restricted;
    private List<TagsBean> tags;
    private boolean is_concluded;
    private int content_count;
    private int total_character_count;
    private String display_text;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public int getRestrict() {
        return restrict;
    }

    public void setRestrict(int restrict) {
        this.restrict = restrict;
    }

    public int getX_restrict() {
        return x_restrict;
    }

    public void setX_restrict(int x_restrict) {
        this.x_restrict = x_restrict;
    }

    public boolean isIs_original() {
        return is_original;
    }

    public void setIs_original(boolean is_original) {
        this.is_original = is_original;
    }

    public ImageUrlsBean getImage_urls() {
        return image_urls;
    }

    public void setImage_urls(ImageUrlsBean image_urls) {
        this.image_urls = image_urls;
    }

    public String getCreate_date() {
        return create_date;
    }

    public void setCreate_date(String create_date) {
        this.create_date = create_date;
    }

    public int getPage_count() {
        return page_count;
    }

    public void setPage_count(int page_count) {
        this.page_count = page_count;
    }

    public int getText_length() {
        return text_length;
    }

    public void setText_length(int text_length) {
        this.text_length = text_length;
    }

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }

    public boolean isIs_bookmarked() {
        return is_bookmarked;
    }

    public void setIs_bookmarked(boolean is_bookmarked) {
        this.is_bookmarked = is_bookmarked;
    }

    public int getTotal_bookmarks() {
        return total_bookmarks;
    }

    public void setTotal_bookmarks(int total_bookmarks) {
        this.total_bookmarks = total_bookmarks;
    }

    public int getTotal_view() {
        return total_view;
    }

    public void setTotal_view(int total_view) {
        this.total_view = total_view;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int getTotal_comments() {
        return total_comments;
    }

    public void setTotal_comments(int total_comments) {
        this.total_comments = total_comments;
    }

    public boolean isIs_muted() {
        return is_muted;
    }

    public void setIs_muted(boolean is_muted) {
        this.is_muted = is_muted;
    }

    public boolean isIs_mypixiv_only() {
        return is_mypixiv_only;
    }

    public void setIs_mypixiv_only(boolean is_mypixiv_only) {
        this.is_mypixiv_only = is_mypixiv_only;
    }

    public boolean isIs_x_restricted() {
        return is_x_restricted;
    }

    public void setIs_x_restricted(boolean is_x_restricted) {
        this.is_x_restricted = is_x_restricted;
    }

    public List<TagsBean> getTags() {
        return tags;
    }

    public void setTags(List<TagsBean> tags) {
        this.tags = tags;
    }


    public SeriesBean getSeries() {
        return series;
    }

    public void setSeries(SeriesBean series) {
        this.series = series;
    }

    public boolean isLocalSaved() {
        return isLocalSaved;
    }

    public void setLocalSaved(boolean localSaved) {
        isLocalSaved = localSaved;
    }

    @Override
    public int getItemID() {
        return getId();
    }

    @Override
    public void setItemID(int id) {
        setId(id);
    }

    @Override
    public boolean isItemStared() {
        return isIs_bookmarked();
    }

    @Override
    public void setItemStared(boolean isLiked) {
        setIs_bookmarked(isLiked);
    }

    public boolean isIs_concluded() {
        return is_concluded;
    }

    public void setIs_concluded(boolean is_concluded) {
        this.is_concluded = is_concluded;
    }

    public int getContent_count() {
        return content_count;
    }

    public void setContent_count(int content_count) {
        this.content_count = content_count;
    }

    public int getTotal_character_count() {
        return total_character_count;
    }

    public void setTotal_character_count(int total_character_count) {
        this.total_character_count = total_character_count;
    }

    public String getDisplay_text() {
        return display_text;
    }

    public void setDisplay_text(String display_text) {
        this.display_text = display_text;
    }

    public String getTagString() {
        String result = "";
        if (tags == null || tags.size() == 0) {
            return result;
        }

        for (int i = 0; i < tags.size(); i++) {
            result = result + "*#" + tags.get(i).getName() + ",";
        }

        return result;
    }

    public String[] getTagNames(){
        return tags.stream().map(TagsBean::getName).toArray(String[]::new);
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public boolean isViewable() {
        return viewable;
    }

    public void setViewable(boolean viewable) {
        this.viewable = viewable;
    }

    public String getContentOrder() {
        return contentOrder;
    }

    public void setContentOrder(String contentOrder) {
        this.contentOrder = contentOrder;
    }
}
