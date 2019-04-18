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
     * @param filter
     * @param mode
     * @return
     */
    @GET("/v1/illust/ranking")
    Observable<ListIllustResponse> getRank(@Header("Authorization") String token,
                                           @Query("filter") String filter,
                                           @Query("mode") String mode);


    @GET
    Observable<ListIllustResponse> getNextIllust(@Header("Authorization") String token,
                                           @Url String next_url);




    /**
     * 推荐榜单
     *
     * @param token
     * @param filter
     * @param include_ranking_illusts
     * @return
     */
    @GET("/v1/illust/recommended")
    Observable<ListIllustResponse> getRecmdIllust(@Header("Authorization") String token,
                                                  @Query("filter") String filter,
                                                  @Query("include_ranking_illusts") boolean include_ranking_illusts);



    @GET("/v1/trending-tags/illust")
    Observable<TrendingtagResponse> getHotTags(@Header("Authorization") String token,
                                               @Query("filter") String filter);
}
