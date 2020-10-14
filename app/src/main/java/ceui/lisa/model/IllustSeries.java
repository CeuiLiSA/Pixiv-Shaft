package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.ImageUrlsBean;
import ceui.lisa.models.SeriesBean;
import ceui.lisa.models.UserBean;

public class IllustSeries implements ListShow<IllustsBean> {


    private IllustsBean illust_series_first_illust;
    private List<IllustsBean> illusts;
    private String next_url;
    private SeriesDetail illust_series_detail;

    public IllustsBean getIllust_series_first_illust() {
        return illust_series_first_illust;
    }

    public void setIllust_series_first_illust(IllustsBean illust_series_first_illust) {
        this.illust_series_first_illust = illust_series_first_illust;
    }

    public List<IllustsBean> getIllusts() {
        return illusts;
    }

    public void setIllusts(List<IllustsBean> illusts) {
        this.illusts = illusts;
    }

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public SeriesDetail getIllust_series_detail() {
        return illust_series_detail;
    }

    public void setIllust_series_detail(SeriesDetail illust_series_detail) {
        this.illust_series_detail = illust_series_detail;
    }

    @Override
    public List<IllustsBean> getList() {
        return illusts;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }

    public static class SeriesDetail{
        private int id;
        private int series_work_count;
        private int width;
        private int height;
        private String title;
        private String create_date;
        private String caption;
        private UserBean user;
        private ImageUrlsBean cover_image_urls;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public int getSeries_work_count() {
            return series_work_count;
        }

        public void setSeries_work_count(int series_work_count) {
            this.series_work_count = series_work_count;
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

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getCreate_date() {
            return create_date;
        }

        public void setCreate_date(String create_date) {
            this.create_date = create_date;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }

        public UserBean getUser() {
            return user;
        }

        public void setUser(UserBean user) {
            this.user = user;
        }

        public ImageUrlsBean getCover_image_urls() {
            return cover_image_urls;
        }

        public void setCover_image_urls(ImageUrlsBean cover_image_urls) {
            this.cover_image_urls = cover_image_urls;
        }
    }
}
