package ceui.lisa.response;

import java.util.List;

import ceui.lisa.interfs.ListShow;

public class TrendingtagResponse implements ListShow<TrendingtagResponse.TrendTagsBean> {
    private List<TrendTagsBean> trend_tags;

    public List<TrendTagsBean> getTrend_tags() {
        return this.trend_tags;
    }

    public void setTrend_tags(List<TrendTagsBean> paramList) {
        this.trend_tags = paramList;
    }

    @Override
    public List<TrendTagsBean> getList() {
        return trend_tags;
    }

    @Override
    public String getNextUrl() {
        return null;
    }

    public static class TrendTagsBean {
        /**
         * tag : ポケモン
         * translated_name : 精灵宝可梦
         * illust : {"id":74202270,"title":"この世界に生まれた時から","type":"illust","image_urls":{"square_medium":"https://i.pximg.net/c/540x540_10_webp/img-master/img/2019/04/14/20/24/04/74202270_p0_square1200.jpg","medium":"https://i.pximg.net/c/540x540_70/img-master/img/2019/04/14/20/24/04/74202270_p0_master1200.jpg","large":"https://i.pximg.net/c/600x1200_90_webp/img-master/img/2019/04/14/20/24/04/74202270_p0_master1200.jpg"},"caption":"君はもうこの宇宙のひとつの星<br />きっかけひとつで君はもっと輝ける星になれるーー\u2026<br /><br />ミュウツーがまた帰ってくるのがすごく嬉しい。お祝いしたい","restrict":0,"user":{"id":771029,"name":"東みなつ","account":"sdv2032","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2015/05/15/11/21/09/9365246_581eab9e2175d8cce9ea914852cf585d_170.gif"},"is_followed":false},"tags":[{"name":"ポケモン","translated_name":"精灵宝可梦"},{"name":"ミュウツーの逆襲イラコン","translated_name":null},{"name":"ミュウツー","translated_name":"mewtwo"},{"name":"ミュウ","translated_name":"梦幻"},{"name":"なにこの仔かわいい","translated_name":"incredibly cute"}],"tools":[],"create_date":"2019-04-14T20:24:04+09:00","page_count":1,"width":995,"height":1543,"sanity_level":2,"x_restrict":0,"series":null,"meta_single_page":{"original_image_url":"https://i.pximg.net/img-original/img/2019/04/14/20/24/04/74202270_p0.png"},"meta_pages":[],"total_view":13452,"total_bookmarks":1038,"is_bookmarked":false,"visible":true,"is_muted":false}
         */

        private String tag;
        private String translated_name;
        private IllustsBean illust;

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public String getTranslated_name() {
            return translated_name;
        }

        public void setTranslated_name(String translated_name) {
            this.translated_name = translated_name;
        }

        public IllustsBean getIllust() {
            return illust;
        }

        public void setIllust(IllustsBean illust) {
            this.illust = illust;
        }

    }
}
