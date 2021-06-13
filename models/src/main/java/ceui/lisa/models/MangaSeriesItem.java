package ceui.lisa.models;

import java.io.Serializable;

public class MangaSeriesItem implements Serializable {

    /**
     * id : 79357
     * title : 告白シリーズ
     * caption : いろんな男女のいちゃいちゃをまとめてます！
     * cover_image_urls : {"medium":"https://i.pximg.net/c/782x410_80_a2_g5/illust-series-cover-original/img/2020/09/08/16/42/38/qVeCw8ERgtlwQD3ax1Di4Ec295wiVRdM.jpg"}
     * series_work_count : 21
     * create_date : 2020-04-12T15:04:02+09:00
     * width : 760
     * height : 428
     * user : {"id":13651304,"name":"八木戸マト（焼きトマト）","account":"sormanngaseisaku","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2019/07/10/06/15/51/15988644_60085643340b7e91019dbdbe3ec61c61_170.png"},"is_followed":false}
     */

    private int id;
    private String title;
    private String caption;
    private ImageUrlsBean cover_image_urls;
    private int series_work_count;
    private String create_date;
    private int width;
    private int height;
    private UserBean user;

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

    public ImageUrlsBean getCover_image_urls() {
        return cover_image_urls;
    }

    public void setCover_image_urls(ImageUrlsBean cover_image_urls) {
        this.cover_image_urls = cover_image_urls;
    }

    public int getSeries_work_count() {
        return series_work_count;
    }

    public void setSeries_work_count(int series_work_count) {
        this.series_work_count = series_work_count;
    }

    public String getCreate_date() {
        return create_date;
    }

    public void setCreate_date(String create_date) {
        this.create_date = create_date;
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

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }
}
