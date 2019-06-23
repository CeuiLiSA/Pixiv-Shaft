package ceui.lisa.http;

import ceui.lisa.model.ArticalResponse;
import ceui.lisa.model.IllustCommentsResponse;
import ceui.lisa.model.IllustSearchResponse;
import ceui.lisa.model.NullResponse;
import ceui.lisa.model.GifResponse;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.TrendingtagResponse;
import ceui.lisa.model.UserDetailResponse;
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


    @GET("/v2/illust/related?filter=for_android")
    Observable<ListIllustResponse> relatedIllust(@Header("Authorization") String token,
                                                @Query("illust_id") int illust_id);


    /**
     * 推荐用户
     *
     * @param token
     * @return
     */
    @GET("/v1/user/recommended?filter=for_android")
    Observable<ListUserResponse> getRecmdUser(@Header("Authorization") String token);



    // /v1/user/bookmarks/illust?user_id=24218478&restrict=public HTTP/1.1
    @GET("/v1/user/bookmarks/illust")
    Observable<ListIllustResponse> getUserLikeIllust(@Header("Authorization") String token,
                                                 @Query("user_id") int user_id,
                                                     @Query("restrict") String restrict);

    @GET("/v1/user/illusts?filter=for_android")
    Observable<ListIllustResponse> getUserSubmitIllust(@Header("Authorization") String token,
                                                     @Query("user_id") int user_id,
                                                     @Query("type") String type);


    @GET("/v2/illust/follow?restrict=public")
    Observable<ListIllustResponse> getFollowUserIllust(@Header("Authorization") String token);


    @GET("/v1/spotlight/articles?filter=for_android&category=all&offset=10")
    Observable<ArticalResponse> getArticals(@Header("Authorization") String token);




    ///v1/user/detail?filter=for_android&user_id=24218478
    @GET("/v1/user/detail?filter=for_android")
    Observable<UserDetailResponse> getUserDetail(@Header("Authorization") String token,
                                                 @Query("user_id") int user_id);



    //  /v1/ugoira/metadata?illust_id=47297805
    @GET("/v1/ugoira/metadata")
    Observable<GifResponse> getGifPackage(@Header("Authorization") String token,
                                          @Query("illust_id") int illust_id);













    @GET
    Observable<ListUserResponse> getNextUser(@Header("Authorization") String token,
                                             @Url String next_url);



    @GET
    Observable<ListIllustResponse> getNextIllust(@Header("Authorization") String token,
                                                 @Url String next_url);

    @GET
    Observable<ArticalResponse> getNextArticals(@Header("Authorization") String token,
                                                 @Url String next_url);


    @FormUrlEncoded
    @POST("/v1/user/follow/add")
    Observable<NullResponse> postFollow(@Header("Authorization") String token,
                                        @Field("user_id") int user_id,
                                        @Field("restrict") String followType);

    @FormUrlEncoded
    @POST("/v1/user/follow/delete")
    Observable<NullResponse> postUnFollow(@Header("Authorization") String token,
                                          @Field("user_id") int user_id);


    /**
     * 获取userid 所关注的人
     *
     * @param token
     * @param user_id
     * @param restrict
     * @return
     */
    @GET("/v1/user/following?filter=for_android")
    Observable<ListUserResponse> getFollowUser(@Header("Authorization") String token,
                                               @Query("user_id") int user_id,
                                               @Query("restrict") String restrict);



    @GET("/v1/illust/comments")
    Observable<IllustCommentsResponse> getComment(@Header("Authorization") String token,
                                                  @Query("illust_id") int illust_id);


    @GET
    Observable<IllustCommentsResponse> getNextComment(@Header("Authorization") String token,
                                                      @Url String nextUrl);


    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    Observable<NullResponse> postComment(@Header("Authorization") String token,
                                         @Field("illust_id") int illust_id,
                                         @Field("comment") String comment,
                                         @Field("parent_comment_id") int parent_comment_id);

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    Observable<NullResponse> postLike(@Header("Authorization") String token,
                                         @Field("illust_id") int illust_id,
                                         @Field("restrict") String restrict);

    @FormUrlEncoded
    @POST("v1/illust/bookmark/delete")
    Observable<NullResponse> postDislike(@Header("Authorization") String token,
                                      @Field("illust_id") int illust_id);


    @GET("v1/illust/detail?filter=for_android")
    Observable<IllustSearchResponse> getIllustByID(@Header("Authorization") String token,
                                                   @Query("illust_id") int illust_id);


    @GET("/v1/search/user?filter=for_android")
    Observable<ListUserResponse> searchUser(@Header("Authorization") String token,
                                            @Query("word") String word);


}
