package ceui.lisa.model;

import ceui.lisa.models.IllustsBean;

public class IllustSearchResponse {


    /**
     * illust : {"id":41318662,"title":"ワスレナグサの花言葉","type":"illust","image_urls":{"square_medium":"https://i.pximg.net/c/540x540_10_webp/img-master/img/2014/02/01/17/53/55/41318662_p0_square1200.jpg","medium":"https://i.pximg.net/c/540x540_70/img-master/img/2014/02/01/17/53/55/41318662_p0_master1200.jpg","large":"https://i.pximg.net/c/600x1200_90_webp/img-master/img/2014/02/01/17/53/55/41318662_p0_master1200.jpg"},"caption":"","restrict":0,"user":{"id":2131305,"name":"NORICOPO＠単行本発売中","account":"nori0w0","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2019/06/03/18/36/27/15846040_8a8e55f1b3cf2934dafc9e2a8cbd2769_170.png"},"is_followed":false},"tags":[{"name":"オリジナル","translated_name":"原创"},{"name":"ふつくしい","translated_name":"太美了"},{"name":"寒色系","translated_name":null},{"name":"なにこれ素敵","translated_name":"卧槽美哭"},{"name":"ネイビー","translated_name":"navy"},{"name":"私を忘れないで","translated_name":null},{"name":"花言葉:真実の恋、真実の愛","translated_name":null},{"name":"勿忘草","translated_name":null},{"name":"オリジナル3000users入り","translated_name":"原创3000收藏"}],"tools":[],"create_date":"2014-02-01T17:53:55+09:00","page_count":1,"width":847,"height":879,"sanity_level":2,"x_restrict":0,"series":null,"meta_single_page":{"original_image_url":"https://i.pximg.net/img-original/img/2014/02/01/17/53/55/41318662_p0.jpg"},"meta_pages":[],"total_view":29716,"total_bookmarks":5329,"is_bookmarked":false,"visible":true,"is_muted":false,"total_comments":4}
     */

    private IllustsBean illust;

    public IllustsBean getIllust() {
        return illust;
    }

    public void setIllust(IllustsBean illust) {
        this.illust = illust;
    }

}
