package ceui.lisa.models;

import java.io.Serializable;
import java.util.List;

public class NovelDetail implements Serializable {


    /**
     * novel_marker : {}
     * novel_text : 注意事項
     * <p>
     * 前回を見なくてもある程度は分かるようになっていますが、できれば見ていてください。
     * <p>
     * ・これはFGOとDCのクロスオーバー作品です
     * <p>
     * To be continue..
     * series_prev : {"id":9972572,"title":"導入","caption":"FGO+.","restrict":0,"x_restrict":0,"is_original":false,"image_urls":{"square_medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_7_128x128.jpg","medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_7_176mw.jpg","large":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_7_240mw.jpg"},"create_date":"2018-08-10T16:53:18+09:00","tags":[{"name":"Fate/GrandOrder","translated_name":"命运－冠位指定","added_by_uploaded_user":true},{"name":"名探偵コナン","translated_name":"名侦探柯南","added_by_uploaded_user":true},{"name":"クロスオーバー","translated_name":"跨界作品","added_by_uploaded_user":true},{"name":"混合小説1000users入り","translated_name":null,"added_by_uploaded_user":true},{"name":"ぐだ男","translated_name":"咕哒男","added_by_uploaded_user":false},{"name":"混合小説500users入り","translated_name":null,"added_by_uploaded_user":true}],"page_count":6,"text_length":5322,"user":{"id":14363729,"name":"川笠汀","account":"minosato4126","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2018/10/04/11/48/19/14855831_e89a14b2dc42d9015af7979c33ca8069_170.png"},"is_followed":false},"series":{"id":1002672,"title":"Fate/DC"},"is_bookmarked":false,"total_bookmarks":1728,"total_view":32461,"visible":true,"total_comments":1,"is_muted":false,"is_mypixiv_only":false,"is_x_restricted":false}
     * series_next : {"id":10095457,"title":"02. Research of the truth","caption":"毎度毎度閲覧ありろ","restrict":0,"x_restrict":0,"is_original":false,"image_urls":{"square_medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_6_128x128.jpg","medium":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_6_176mw.jpg","large":"https://s.pximg.net/common/images/novel_thumb/novel_thumb_6_240mw.jpg"},"create_date":"2018-09-08T19:24:26+09:00","tags":[{"name":"Fate/GrandOrder","translated_name":"命运－冠位指定","added_by_uploaded_user":true},{"name":"混合小説300users入り","translated_name":null,"added_by_uploaded_user":true},{"name":"混合小説100users入り","translated_name":null,"added_by_uploaded_user":true},{"name":"クロスオーバー","translated_name":"跨界作品","added_by_uploaded_user":true},{"name":"名探偵コナン","translated_name":"名侦探柯南","added_by_uploaded_user":true},{"name":"作者がピンチ","translated_name":null,"added_by_uploaded_user":true}],"page_count":11,"text_length":11988,"user":{"id":14363729,"name":"川笠汀","account":"minosato4126","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2018/10/04/11/48/19/14855831_e89a14b2dc42d9015af7979c33ca8069_170.png"},"is_followed":false},"series":{"id":1002672,"title":"Fate/DC"},"is_bookmarked":false,"total_bookmarks":401,"total_view":16986,"visible":true,"total_comments":21,"is_muted":false,"is_mypixiv_only":false,"is_x_restricted":false}
     */

    private NovelMarkerBean novel_marker;
    private String novel_text;
    private NovelBean series_prev;
    private NovelBean series_next;
    private List<NovelChapterBean> parsedChapters = null;

    public NovelMarkerBean getNovel_marker() {
        return novel_marker;
    }

    public void setNovel_marker(NovelMarkerBean novel_marker) {
        this.novel_marker = novel_marker;
    }

    public String getNovel_text() {
        return novel_text;
    }

    public void setNovel_text(String novel_text) {
        this.novel_text = novel_text;
    }

    public NovelBean getSeries_prev() {
        return series_prev;
    }

    public void setSeries_prev(NovelBean series_prev) {
        this.series_prev = series_prev;
    }

    public NovelBean getSeries_next() {
        return series_next;
    }

    public void setSeries_next(NovelBean series_next) {
        this.series_next = series_next;
    }

    public List<NovelChapterBean> getParsedChapters() {
        return parsedChapters;
    }

    public void setParsedChapters(List<NovelChapterBean> parsedChapters) {
        this.parsedChapters = parsedChapters;
    }

    public static class NovelMarkerBean implements Serializable {
        private int page = 0;

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }
    }

    public static class NovelChapterBean implements Serializable {
        private int chapterIndex; // 1 based value
        private String chapterName;
        private String chapterContent;

        public int getChapterIndex() {
            return chapterIndex;
        }

        public void setChapterIndex(int chapterIndex) {
            this.chapterIndex = chapterIndex;
        }

        public String getChapterName() {
            return chapterName;
        }

        public void setChapterName(String chapterName) {
            this.chapterName = chapterName;
        }

        public String getChapterContent() {
            return chapterContent;
        }

        public void setChapterContent(String chapterContent) {
            this.chapterContent = chapterContent;
        }
    }
}
