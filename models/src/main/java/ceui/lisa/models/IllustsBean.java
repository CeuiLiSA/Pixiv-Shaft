package ceui.lisa.models;

import android.text.TextUtils;

import java.io.File;
import java.io.Serializable;
import java.util.List;

public class IllustsBean implements Serializable, Starable {
    /**
     * id : 73949833
     * title : 命に繋がる魂の絆
     * type : illust
     * image_urls : {"square_medium":"https://i.pximg.net/c/540x540_10_webp/img-master/img/2019/03/30/16/33/50/73949833_p0_square1200.jpg","medium":"https://i.pximg.net/c/540x540_70/img-master/img/2019/03/30/16/33/50/73949833_p0_master1200.jpg","large":"https://i.pximg.net/c/600x1200_90_webp/img-master/img/2019/03/30/16/33/50/73949833_p0_master1200.jpg"}
     * caption : この奇跡が授けた命とお前は余が守る。
     * restrict : 0
     * user : {"id":74184,"name":"零＠通販始めた","account":"sanbonzakura","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2017/04/27/10/00/38/12474975_a0a699ea19f387df0f98bc5a9b7d26d3_170.png"},"is_followed":false}
     * tags : [{"name":"山の女王ファリア","translated_name":"山之女王 法俐雅"},{"name":"レッドヴァルの戦い【赤】","translated_name":"雷德瓦尔之战【红】"},{"name":"pixivファンタジアLS","translated_name":"pixiv Fantasia: Last Saga"},{"name":"ファイアランド","translated_name":"法尔蓝"},{"name":"火と鉄の王エゼル","translated_name":"火与铁之王 爱杰尔"},{"name":"ふつくしい","translated_name":"太美了"},{"name":"ピクファン1000users入り","translated_name":"PF 1000收藏"}]
     * tools : []
     * create_date : 2019-03-30T16:33:50+09:00
     * page_count : 1
     * width : 1000
     * height : 2000
     * sanity_level : 2
     * x_restrict : 0
     * series : null
     * meta_single_page : {"original_image_url":"https://i.pximg.net/img-original/img/2019/03/30/16/33/50/73949833_p0.jpg"}
     * meta_pages : []
     * total_view : 75371
     * total_bookmarks : 7771
     * is_bookmarked : false
     * visible : true
     * is_muted : false
     */

    private int id;
    private int gifDelay;
    private String title;
    private String type;
    private ImageUrlsBean image_urls;
    private String caption;
    private int restrict;
    private boolean isChecked = false;
    private UserBean user;
    private String create_date;
    private int page_count;
    private int width;
    private int height;
    private int sanity_level;
    private int x_restrict;
    private SeriesBean series;
    private MetaSinglePageBean meta_single_page;
    private int total_view;
    private int total_bookmarks;
    private boolean is_bookmarked;
    private boolean visible;
    private boolean is_muted;
    private List<TagsBean> tags;
    private List<String> tools;
    private List<MetaPagesBean> meta_pages;
    private boolean isShield; //是否被屏蔽

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isGif() {
        return "ugoira".equals(type);
    }

    public ImageUrlsBean getImage_urls() {
        return image_urls;
    }

    public void setImage_urls(ImageUrlsBean image_urls) {
        this.image_urls = image_urls;
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

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
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

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getSanity_level() {
        return sanity_level;
    }

    public void setSanity_level(int sanity_level) {
        this.sanity_level = sanity_level;
    }

    public int getX_restrict() {
        return x_restrict;
    }

    public void setX_restrict(int x_restrict) {
        this.x_restrict = x_restrict;
    }

    public SeriesBean getSeries() {
        return series;
    }

    public void setSeries(SeriesBean series) {
        this.series = series;
    }

    public MetaSinglePageBean getMeta_single_page() {
        return meta_single_page;
    }

    public void setMeta_single_page(MetaSinglePageBean meta_single_page) {
        this.meta_single_page = meta_single_page;
    }

    public int getTotal_view() {
        return total_view;
    }

    public void setTotal_view(int total_view) {
        this.total_view = total_view;
    }

    public int getTotal_bookmarks() {
        return total_bookmarks;
    }

    public void setTotal_bookmarks(int total_bookmarks) {
        this.total_bookmarks = total_bookmarks;
    }

    public boolean isIs_bookmarked() {
        return is_bookmarked;
    }

    public void setIs_bookmarked(boolean is_bookmarked) {
        this.is_bookmarked = is_bookmarked;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isIs_muted() {
        return is_muted;
    }

    public void setIs_muted(boolean is_muted) {
        this.is_muted = is_muted;
    }

    public List<TagsBean> getTags() {
        return tags;
    }

    public void setTags(List<TagsBean> tags) {
        this.tags = tags;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public List<MetaPagesBean> getMeta_pages() {
        return meta_pages;
    }

    public void setMeta_pages(List<MetaPagesBean> meta_pages) {
        this.meta_pages = meta_pages;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setChecked(boolean checked) {
        isChecked = checked;
    }

    public String getSize() {
        return getWidth() + "px * " + getHeight() + "px";
    }

    public int getGifDelay() {
        return gifDelay;
    }

    public void setGifDelay(int gifDelay) {
        this.gifDelay = gifDelay;
    }


    @Override
    public String toString() {
        return "IllustsBean{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", type='" + type + '\'' +
                ", image_urls=" + image_urls +
                ", caption='" + caption + '\'' +
                ", restrict=" + restrict +
                ", user=" + user +
                ", create_date='" + create_date + '\'' +
                ", page_count=" + page_count +
                ", width=" + width +
                ", height=" + height +
                ", sanity_level=" + sanity_level +
                ", x_restrict=" + x_restrict +
                ", series=" + series +
                ", meta_single_page=" + meta_single_page +
                ", total_view=" + total_view +
                ", total_bookmarks=" + total_bookmarks +
                ", is_bookmarked=" + is_bookmarked +
                ", visible=" + visible +
                ", is_muted=" + is_muted +
                ", tags=" + tags +
                ", tools=" + tools +
                ", meta_pages=" + meta_pages +
                '}';
    }

    public boolean isShield() {
        return isShield;
    }

    public void setShield(boolean shield) {
        isShield = shield;
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
    public void setItemStared(boolean isLike) {
        setIs_bookmarked(isLike);
    }
}
