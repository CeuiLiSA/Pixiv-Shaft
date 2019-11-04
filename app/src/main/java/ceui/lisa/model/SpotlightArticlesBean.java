package ceui.lisa.model;

public class SpotlightArticlesBean {
    /**
     * id : 4371
     * title : 1个小时创造的奇迹！难以相信是one draw的插画作品特辑
     * pure_title : 难以相信是one draw的插画作品特辑
     * thumbnail : https://i.pximg.net/c/540x540_70/img-master/img/2017/05/13/19/34/51/62879517_p0_master1200.jpg
     * article_url : https://www.pixivision.net/zh/a/4371
     * publish_date : 2019-05-22T18:00:00+09:00
     * category : spotlight
     * subcategory_label : 插画
     */

    private int id;
    private String title;
    private String pure_title;
    private String thumbnail;
    private String article_url;
    private String publish_date;
    private String category;
    private String subcategory_label;

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

    public String getPure_title() {
        return pure_title;
    }

    public void setPure_title(String pure_title) {
        this.pure_title = pure_title;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    public String getArticle_url() {
        return article_url;
    }

    public void setArticle_url(String article_url) {
        this.article_url = article_url;
    }

    public String getPublish_date() {
        return publish_date;
    }

    public void setPublish_date(String publish_date) {
        this.publish_date = publish_date;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubcategory_label() {
        return subcategory_label;
    }

    public void setSubcategory_label(String subcategory_label) {
        this.subcategory_label = subcategory_label;
    }
}
