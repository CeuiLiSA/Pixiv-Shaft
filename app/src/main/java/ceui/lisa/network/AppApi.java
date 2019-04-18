package ceui.lisa.network;

import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.TrendingtagResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.Url;

public interface AppApi {

    /**
     * 获取排行榜
     *
     * @param mode
     * @return
     */
    @GET("/v1/illust/ranking?filter=for_android")
    Observable<ListIllustResponse> getRank(@Header("Authorization") String token,
                                           @Query("mode") String mode);


    @GET
    Observable<ListIllustResponse> getNextIllust(@Header("Authorization") String token,
                                           @Url String next_url);




    /**
     * 推荐榜单
     *
     * @param token
     * @param include_ranking_illusts
     * @return
     */
    @GET("/v1/illust/recommended?include_privacy_policy=true&filter=for_android")
    Observable<ListIllustResponse> getRecmdIllust(@Header("Authorization") String token,
                                                  @Query("include_ranking_illusts") boolean include_ranking_illusts);



    @GET("/v1/trending-tags/illust?filter=for_android&include_translated_tag_results=true")
    Observable<TrendingtagResponse> getHotTags(@Header("Authorization") String token);


    /**
     * 原版app登录时候的背景墙
     *
     * @param token
     * @return
     */
    @GET("/v1/walkthrough/illusts?filter=for_android")
    Observable<ListIllustResponse> getLoginBg(@Header("Authorization") String token);


    /**
     *    /v1/search/illust?
     *    filter=for_android&
     *    include_translated_tag_results=true&
     *    word=%E8%89%A6%E9%9A%8A%E3%81%93%E3%82%8C%E3%81%8F%E3%81%97%E3%82%87%E3%82%93&
     *    sort=date_desc& 最新
     *    sort=date_asc& 旧的在前面
     *    search_target=exact_match_for_tags 标签完全匹配
     *    search_target=partial_match_for_tags 标签部分匹配
     *    search_target=title_and_caption 标题或简介
     */
    @GET("/v1/search/illust?filter=for_android")
    Observable<ListIllustResponse> searchIllust(@Header("Authorization") String token,
                                                @Query("word") String word,
                                                @Query("sort") String sort,
                                                @Query("search_target") String search_target);
}
