package ceui.lisa.response;

import java.util.List;

import ceui.lisa.interfaces.ListShow;

public class ArticalResponse implements ListShow<ArticalResponse.SpotlightArticlesBean> {


    /**
     * spotlight_articles : [{"id":4371,"title":"1个小时创造的奇迹！难以相信是one draw的插画作品特辑","pure_title":"难以相信是one draw的插画作品特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2017/05/13/19/34/51/62879517_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4371","publish_date":"2019-05-22T18:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4370,"title":"勇气的证明。有伤疤的女性角色特辑","pure_title":"有伤疤的女性角色特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2017/03/29/00/26/15/62142272_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4370","publish_date":"2019-05-22T17:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4581,"title":"逃跑？还是袭击？ 沉浸于惊悚游戏《第五人格（IdentityV）》的同人特辑","pure_title":" 沉浸于惊悚游戏《第五人格（IdentityV）》的同人特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2018/08/10/17/13/28/70125122_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4581","publish_date":"2019-05-21T19:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4368,"title":"绿色与阳光的庭园。温室特辑","pure_title":"温室特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2014/07/18/02/46/49/44771844_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4368","publish_date":"2019-05-21T18:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4369,"title":"古典音色。留声机特辑","pure_title":"留声机特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2016/08/11/01/22/58/58373618_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4369","publish_date":"2019-05-21T17:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4656,"title":"恭喜！大家辛苦了！第8届\u201c灰姑娘女孩总选举\u201dTOP10的同人作品特辑","pure_title":"第8届\u201c灰姑娘女孩总选举\u201dTOP10的同人作品特辑","thumbnail":"https://i.pximg.net/imgaz/upload/20190520/611935491.jpg","article_url":"https://www.pixivision.net/zh/a/4656","publish_date":"2019-05-20T19:15:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4642,"title":"中国推出的手机游戏\u201c明日方舟（ARK NIGHTS）\u201d是什么？【热门关键词】","pure_title":"中国推出的手机游戏\u201c明日方舟（ARK NIGHTS）\u201d是什么？【热门关键词】","thumbnail":"https://i.pximg.net/imgaz/upload/20190520/475735383.jpg","article_url":"https://www.pixivision.net/zh/a/4642","publish_date":"2019-05-20T19:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4426,"title":"带我一起去银河吧！宇宙与少女特辑","pure_title":"宇宙与少女特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2019/02/04/00/03/59/72994793_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4426","publish_date":"2019-05-20T18:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4387,"title":"以白色遮盖。绷带少女特辑","pure_title":"绷带少女特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2017/11/13/00/34/22/65872002_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4387","publish_date":"2019-05-20T17:00:00+09:00","category":"spotlight","subcategory_label":"插画"},{"id":4411,"title":"雍容闲雅♡美丽的和风少女特辑","pure_title":"美丽的和风少女特辑","thumbnail":"https://i.pximg.net/c/540x540_70/img-master/img/2015/02/27/01/16/34/48983820_p0_master1200.jpg","article_url":"https://www.pixivision.net/zh/a/4411","publish_date":"2019-05-19T18:00:00+09:00","category":"spotlight","subcategory_label":"插画"}]
     * next_url : https://app-api.pixiv.net/v1/spotlight/articles?filter=for_android&category=all&offset=10
     */

    private String next_url;
    private List<SpotlightArticlesBean> spotlight_articles;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public List<SpotlightArticlesBean> getSpotlight_articles() {
        return spotlight_articles;
    }

    public void setSpotlight_articles(List<SpotlightArticlesBean> spotlight_articles) {
        this.spotlight_articles = spotlight_articles;
    }

    @Override
    public List<SpotlightArticlesBean> getList() {
        return spotlight_articles;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }

    public static class SpotlightArticlesBean {
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
}
