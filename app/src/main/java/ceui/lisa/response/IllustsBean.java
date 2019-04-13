package ceui.lisa.response;

import java.io.Serializable;
import java.util.List;

public class IllustsBean implements Serializable {
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
    private String title;
    private String type;
    private ImageUrlsBean image_urls;
    private String caption;
    private int restrict;
    private UserBean user;
    private String create_date;
    private int page_count;
    private int width;
    private int height;
    private int sanity_level;
    private int x_restrict;
    private Object series;
    private MetaSinglePageBean meta_single_page;
    private int total_view;
    private int total_bookmarks;
    private boolean is_bookmarked;
    private boolean visible;
    private boolean is_muted;
    private List<TagsBean> tags;
    private List<?> tools;
    private List<MetaPagesBean> meta_pages;

    public static class MetaPagesBean implements Serializable{
        /**
         * image_urls : {"square_medium":"https://i.pximg.net/c/360x360_70/img-master/img/2019/04/03/21/13/11/74027091_p0_square1200.jpg","medium":"https://i.pximg.net/c/540x540_70/img-master/img/2019/04/03/21/13/11/74027091_p0_master1200.jpg","large":"https://i.pximg.net/c/600x1200_90/img-master/img/2019/04/03/21/13/11/74027091_p0_master1200.jpg","original":"https://i.pximg.net/img-original/img/2019/04/03/21/13/11/74027091_p0.png"}
         */

        private ImageUrlsBean image_urls;

        public ImageUrlsBean getImage_urls() {
            return image_urls;
        }

        public void setImage_urls(ImageUrlsBean image_urls) {
            this.image_urls = image_urls;
        }
    }

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

    public Object getSeries() {
        return series;
    }

    public void setSeries(Object series) {
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

    public List<?> getTools() {
        return tools;
    }

    public void setTools(List<?> tools) {
        this.tools = tools;
    }

    public List<MetaPagesBean> getMeta_pages() {
        return meta_pages;
    }

    public void setMeta_pages(List<MetaPagesBean> meta_pages) {
        this.meta_pages = meta_pages;
    }



    public static class UserBean implements Serializable{
        /**
         * id : 74184
         * name : 零＠通販始めた
         * account : sanbonzakura
         * profile_image_urls : {"medium":"https://i.pximg.net/user-profile/img/2017/04/27/10/00/38/12474975_a0a699ea19f387df0f98bc5a9b7d26d3_170.png"}
         * is_followed : false
         */

        private int id;
        private String name;
        private String account;
        private ProfileImageUrlsBean profile_image_urls;
        private boolean is_followed;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public ProfileImageUrlsBean getProfile_image_urls() {
            return profile_image_urls;
        }

        public void setProfile_image_urls(ProfileImageUrlsBean profile_image_urls) {
            this.profile_image_urls = profile_image_urls;
        }

        public boolean isIs_followed() {
            return is_followed;
        }

        public void setIs_followed(boolean is_followed) {
            this.is_followed = is_followed;
        }

        public static class ProfileImageUrlsBean implements Serializable{
            /**
             * medium : https://i.pximg.net/user-profile/img/2017/04/27/10/00/38/12474975_a0a699ea19f387df0f98bc5a9b7d26d3_170.png
             */

            private String medium;

            public String getMedium() {
                return medium;
            }

            public void setMedium(String medium) {
                this.medium = medium;
            }
        }
    }
}
