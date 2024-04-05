package ceui.lisa.http;

import java.util.HashMap;
import java.util.List;

import ceui.lisa.model.ListArticle;
import ceui.lisa.model.ListBookmarkTag;
import ceui.lisa.model.ListComment;
import ceui.lisa.model.ListIllust;
import ceui.lisa.model.ListLive;
import ceui.lisa.model.ListMangaOfSeries;
import ceui.lisa.model.ListMangaSeries;
import ceui.lisa.model.ListNovel;
import ceui.lisa.model.ListNovelOfSeries;
import ceui.lisa.model.ListNovelSeries;
import ceui.lisa.model.ListSimpleUser;
import ceui.lisa.model.ListTag;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.model.ListUser;
import ceui.lisa.model.RecmdIllust;
import ceui.lisa.models.CommentHolder;
import ceui.lisa.models.GifResponse;
import ceui.lisa.models.IllustSearchResponse;
import ceui.lisa.models.MutedHistory;
import ceui.lisa.models.NovelDetail;
import ceui.lisa.models.NovelSearchResponse;
import ceui.lisa.models.NullResponse;
import ceui.lisa.models.Preset;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.models.UserFollowDetail;
import ceui.lisa.models.UserState;
import io.reactivex.Observable;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

public interface AppApi {

    //用作各个页面请求数据
    String API_BASE_URL = "https://app-api.pixiv.net/";

    /**
     * 获取排行榜
     *
     * @param mode
     * @return
     */
    @GET("v1/illust/ranking?filter=for_android")
    Observable<ListIllust> getRank(@Header("Authorization") String token,
                                   @Query("mode") String mode,
                                   @Query("date") String date);

    @GET("v1/novel/ranking?filter=for_android")
    Observable<ListNovel> getRankNovel(@Header("Authorization") String token,
                                       @Query("mode") String mode,
                                       @Query("date") String date);

    /**
     * 推荐榜单
     *
     * @param token
     * @return
     */
    @GET("v1/illust/recommended?include_privacy_policy=true&filter=for_android")
    Observable<RecmdIllust> getRecmdIllust(@Header("Authorization") String token, @Query("include_ranking_illusts") boolean include_ranking_illusts);


    @GET("v1/manga/recommended?include_privacy_policy=true&filter=for_android&include_ranking_illusts=true")
    Observable<RecmdIllust> getRecmdManga(@Header("Authorization") String token);

    @GET("v1/novel/recommended?include_privacy_policy=true&filter=for_android&include_ranking_novels=true")
    Observable<ListNovel> getRecmdNovel(@Header("Authorization") String token);

    @GET("v1/novel/follow")
    Observable<ListNovel> getBookedUserSubmitNovel(@Header("Authorization") String token,
                                                   @Query("restrict") String restrict);


    @GET("v1/trending-tags/{type}?filter=for_android&include_translated_tag_results=true")
    Observable<ListTrendingtag> getHotTags(@Header("Authorization") String token,
                                           @Path("type") String type);


    /**
     * 原版app登录时候的背景墙
     *
     * @param token
     * @return
     */
    @GET("v1/walkthrough/illusts?filter=for_android")
    Observable<ListIllust> getLoginBg(@Header("Authorization") String token);


    /**
     * /v1/search/illust?
     * filter=for_android&
     * include_translated_tag_results=true&
     * word=%E8%89%A6%E9%9A%8A%E3%81%93%E3%82%8C%E3%81%8F%E3%81%97%E3%82%87%E3%82%93&
     * sort=date_desc& 最新
     * sort=date_asc& 旧的在前面
     * search_target=exact_match_for_tags 标签完全匹配
     * search_target=partial_match_for_tags 标签部分匹配
     * search_target=title_and_caption 标题或简介
     * start_date 开始时间
     * end_date 结束时间
     */
    @GET("v1/search/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListIllust> searchIllust(@Header("Authorization") String token,
                                        @Query("word") String word,
                                        @Query("sort") String sort,
                                        @Query("start_date") String startDate,
                                        @Query("end_date") String endDate,
                                        @Query("search_target") String search_target);

    /**
     * search_target=exact_match_for_tags,partial_match_for_tags,text(文本),keyword(关键词)
     */
    @GET("v1/search/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListNovel> searchNovel(@Header("Authorization") String token,
                                      @Query("word") String word,
                                      @Query("sort") String sort,
                                      @Query("start_date") String startDate,
                                      @Query("end_date") String endDate,
                                      @Query("search_target") String search_target);


    @GET("v2/illust/related?filter=for_android")
    Observable<ListIllust> relatedIllust(@Header("Authorization") String token,
                                         @Query("illust_id") int illust_id);


    /**
     * 推荐用户
     *
     * @param token
     * @return
     */
    @GET("v1/user/recommended?filter=for_android")
    Observable<ListUser> getRecmdUser(@Header("Authorization") String token);


    @GET("v1/user/bookmarks/illust")
    Observable<ListIllust> getUserLikeIllust(@Header("Authorization") String token,
                                             @Query("user_id") int user_id,
                                             @Query("restrict") String restrict,
                                             @Query("tag") String tag);

    @GET("v1/user/bookmarks/illust")
    Observable<ListIllust> getUserLikeIllust(@Header("Authorization") String token,
                                             @Query("user_id") int user_id,
                                             @Query("restrict") String restrict);

    @GET("v1/user/bookmarks/novel")
    Observable<ListNovel> getUserLikeNovel(@Header("Authorization") String token,
                                           @Query("user_id") int user_id,
                                           @Query("restrict") String restrict,
                                           @Query("tag") String tag);

    @GET("v1/user/bookmarks/novel")
    Observable<ListNovel> getUserLikeNovel(@Header("Authorization") String token,
                                           @Query("user_id") int user_id,
                                           @Query("restrict") String restrict);

    @GET("v1/user/illusts?filter=for_android")
    Observable<ListIllust> getUserSubmitIllust(@Header("Authorization") String token,
                                               @Query("user_id") int user_id,
                                               @Query("type") String type);

    @GET("v1/user/novels")
    Observable<ListNovel> getUserSubmitNovel(@Header("Authorization") String token,
                                             @Query("user_id") int user_id);


    @GET("v2/illust/follow")
    Observable<ListIllust> getFollowUserIllust(@Header("Authorization") String token,
                                               @Query("restrict") String restrict);


    @GET("v1/spotlight/articles?filter=for_android")
    Observable<ListArticle> getArticles(@Header("Authorization") String token,
                                        @Query("category") String category);


    ///v1/user/detail?filter=for_android&user_id=24218478
    @GET("v1/user/detail?filter=for_android")
    Observable<UserDetailResponse> getUserDetail(@Header("Authorization") String token,
                                                 @Query("user_id") int user_id);


    //  /v1/ugoira/metadata?illust_id=47297805
    @GET("v1/ugoira/metadata")
    Observable<GifResponse> getGifPackage(@Header("Authorization") String token,
                                          @Query("illust_id") int illust_id);


    @FormUrlEncoded
    @POST("v1/user/follow/add")
    Observable<NullResponse> postFollow(@Header("Authorization") String token,
                                        @Field("user_id") int user_id,
                                        @Field("restrict") String followType);

    @FormUrlEncoded
    @POST("v1/user/follow/delete")
    Observable<NullResponse> postUnFollow(@Header("Authorization") String token,
                                          @Field("user_id") int user_id);

    @GET("v1/user/follow/detail")
    Observable<UserFollowDetail> getFollowDetail(@Header("Authorization") String token,
                                                 @Query("user_id") int user_id);


    /**
     * 获取userid 所关注的人
     *
     * @param token
     * @param user_id
     * @param restrict
     * @return
     */
    @GET("v1/user/following?filter=for_android")
    Observable<ListUser> getFollowUser(@Header("Authorization") String token,
                                       @Query("user_id") int user_id,
                                       @Query("restrict") String restrict);


    //获取关注 这个userid 的人
    @GET("v1/user/follower?filter=for_android")
    Observable<ListUser> getWhoFollowThisUser(@Header("Authorization") String token,
                                              @Query("user_id") int user_id);


    @GET("/v3/illust/comments")
    Observable<ListComment> getIllustComment(@Header("Authorization") String token,
                                             @Query("illust_id") int illust_id);

    @GET("v3/novel/comments")
    Observable<ListComment> getNovelComment(@Header("Authorization") String token,
                                       @Query("novel_id") int novel_id);

    @GET
    Observable<ListComment> getNextComment(@Header("Authorization") String token,
                                           @Url String nextUrl);


    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    Observable<CommentHolder> postIllustComment(@Header("Authorization") String token,
                                                @Field("illust_id") int illust_id,
                                                @Field("comment") String comment);

    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    Observable<CommentHolder> postIllustComment(@Header("Authorization") String token,
                                                @Field("illust_id") int illust_id,
                                                @Field("comment") String comment,
                                                @Field("parent_comment_id") int parent_comment_id);

    @FormUrlEncoded
    @POST("v1/novel/comment/add")
    Observable<CommentHolder> postNovelComment(@Header("Authorization") String token,
                                          @Field("novel_id") int novel_id,
                                          @Field("comment") String comment);

    @FormUrlEncoded
    @POST("v1/novel/comment/add")
    Observable<CommentHolder> postNovelComment(@Header("Authorization") String token,
                                          @Field("novel_id") int novel_id,
                                          @Field("comment") String comment,
                                          @Field("parent_comment_id") int parent_comment_id);

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    Observable<NullResponse> postLikeIllust(@Header("Authorization") String token,
                                            @Field("illust_id") int illust_id,
                                            @Field("restrict") String restrict);

    @FormUrlEncoded
    @POST("v2/novel/bookmark/add")
    Observable<NullResponse> postLikeNovel(@Header("Authorization") String token,
                                           @Field("novel_id") int novel_id,
                                           @Field("restrict") String restrict);

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    Observable<NullResponse> postLikeIllustWithTags(@Header("Authorization") String token,
                                                    @Field("illust_id") int illust_id,
                                                    @Field("restrict") String restrict,
                                                    @Field("tags[]") String... tags);

    @FormUrlEncoded
    @POST("v2/novel/bookmark/add")
    Observable<NullResponse> postLikeNovelWithTags(@Header("Authorization") String token,
                                                    @Field("novel_id") int novel_id,
                                                    @Field("restrict") String restrict,
                                                    @Field("tags[]") String... tags);

    @FormUrlEncoded
    @POST("v1/illust/bookmark/delete")
    Observable<NullResponse> postDislikeIllust(@Header("Authorization") String token,
                                               @Field("illust_id") int illust_id);

    @FormUrlEncoded
    @POST("v1/novel/bookmark/delete")
    Observable<NullResponse> postDislikeNovel(@Header("Authorization") String token,
                                              @Field("novel_id") int novel_id);


    @GET("v1/illust/detail?filter=for_android")
    Observable<IllustSearchResponse> getIllustByID(@Header("Authorization") String token,
                                                   @Query("illust_id") long illust_id);


    @GET("v1/search/user?filter=for_android")
    Observable<ListUser> searchUser(@Header("Authorization") String token,
                                    @Query("word") String word);


    @GET("v1/search/popular-preview/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListIllust> popularPreview(@Header("Authorization") String token,
                                          @Query("word") String word,
                                          @Query("start_date") String startDate,
                                          @Query("end_date") String endDate,
                                          @Query("search_target") String search_target);

    @GET("v1/search/popular-preview/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListNovel> popularNovelPreview(@Header("Authorization") String token,
                                          @Query("word") String word,
                                          @Query("start_date") String startDate,
                                          @Query("end_date") String endDate,
                                          @Query("search_target") String search_target);


    // v2/search/autocomplete?merge_plain_keyword_results=true&word=%E5%A5%B3%E4%BD%93 HTTP/1.1
    @GET("v2/search/autocomplete?merge_plain_keyword_results=true")
    Observable<ListTrendingtag> searchCompleteWord(@Header("Authorization") String token,
                                                   @Query("word") String word);


    /**
     * 获取所有插画收藏的标签
     */
    //GET v1/user/bookmark-tags/illust?user_id=41531382&restrict=public HTTP/1.1
    @GET("v1/user/bookmark-tags/illust")
    Observable<ListTag> getAllIllustBookmarkTags(@Header("Authorization") String token,
                                                 @Query("user_id") int user_id,
                                                 @Query("restrict") String restrict);

    /**
     * 获取所有小说收藏的标签
     */
    //GET v1/user/bookmark-tags/novel?user_id=41531382&restrict=public HTTP/1.1
    @GET("v1/user/bookmark-tags/novel")
    Observable<ListTag> getAllNovelBookmarkTags(@Header("Authorization") String token,
                                                @Query("user_id") int user_id,
                                                @Query("restrict") String restrict);


    @GET
    Observable<ListTag> getNextTags(@Header("Authorization") String token,
                                    @Url String nextUrl);

    /**
     * 获取单个插画收藏的标签
     */
    @GET("v2/illust/bookmark/detail")
    Observable<ListBookmarkTag> getIllustBookmarkTags(@Header("Authorization") String token,
                                                      @Query("illust_id") int illust_id);
    /**
     * 获取单个小说收藏的标签
     */
    @GET("v2/novel/bookmark/detail")
    Observable<ListBookmarkTag> getNovelBookmarkTags(@Header("Authorization") String token,
                                                      @Query("novel_id") int novel_id);


    /**
     * 获取已屏蔽的标签/用户
     * <p>
     * 这功能感觉做了没啥卵用，因为未开会员的用户只能屏蔽一个标签/用户，
     * <p>
     * 你屏蔽了一个用户，就不能再屏蔽标签，屏蔽了标签，就不能屏蔽用户，而且都只能屏蔽一个，擦
     *
     * @param token
     * @return
     */
    @GET("v1/mute/list")
    Observable<MutedHistory> getMutedHistory(@Header("Authorization") String token);


    //获取好P友
    @GET("v1/user/mypixiv?filter=for_android")
    Observable<ListUser> getNiceFriend(@Header("Authorization") String token,
                                       @Query("user_id") int user_id);

    //获取最新作品
    @GET("v1/illust/new?filter=for_android")
    Observable<ListIllust> getNewWorks(@Header("Authorization") String token,
                                       @Query("content_type") String content_type);

    //获取最新作品
    @GET("v1/novel/new")
    Observable<ListNovel> getNewNovels(@Header("Authorization") String token);


    @GET("/webview/v2/novel")
    Call<ResponseBody> getNovelDetailV2(@Header("Authorization") String token,
                                        @Query("id") long id);


    //获取好P友
    @GET("v1/user/me/state")
    Observable<UserState> getAccountState(@Header("Authorization") String token);

    @Multipart
    @POST("v1/user/profile/edit")
    Observable<NullResponse> updateUserProfile(@Header("Authorization") String token,
                                               @Part List<MultipartBody.Part> parts);


    @GET("v1/live/list")
    Observable<ListLive> getLiveList(@Header("Authorization") String token,
                                     @Query("list_type") String list_type);

    @GET("v1/illust/bookmark/users?filter=for_android")
    Observable<ListSimpleUser> getUsersWhoLikeThisIllust(@Header("Authorization") String token,
                                                         @Query("illust_id") int illust_id);

    @GET("v2/novel/series")
    Observable<ListNovelOfSeries> getNovelSeries(@Header("Authorization") String token,
                                                 @Query("series_id") int series_id);

    @GET("v2/novel/detail")
    Observable<NovelSearchResponse> getNovelByID(@Header("Authorization") String token,
                                                 @Query("novel_id") long novel_id);

    @GET("v1/illust/series?filter=for_android")
    Observable<ListMangaOfSeries> getMangaSeriesById(@Header("Authorization") String token,
                                                     @Query("illust_series_id") int illust_series_id);


    @GET("v1/user/illust-series")
    Observable<ListMangaSeries> getUserMangaSeries(@Header("Authorization") String token,
                                                   @Query("user_id") int user_id);


    @GET("v1/user/novel-series")
    Observable<ListNovelSeries> getUserNovelSeries(@Header("Authorization") String token,
                                                   @Query("user_id") int user_id);

    @FormUrlEncoded
    @POST("v1/user/workspace/edit")
    Observable<NullResponse> editWorkSpace(@Header("Authorization") String token,
                                           @FieldMap HashMap<String, String> fields);


    @GET("v1/user/profile/presets")
    Observable<Preset> getPresets(@Header("Authorization") String token);

    @GET("v2/illust/mypixiv")
    Observable<ListIllust> getNiceFriendIllust(@Header("Authorization") String token);

    @GET("v1/novel/mypixiv")
    Observable<ListNovel> getNiceFriendNovel(@Header("Authorization") String token);


    @GET
    Observable<ListNovelSeries> getNextUserNovelSeries(@Header("Authorization") String token,
                                                       @Url String next_url);

    @GET
    Observable<ListMangaSeries> getNextUserMangaSeries(@Header("Authorization") String token,
                                                       @Url String next_url);

    @GET
    Observable<ListUser> getNextUser(@Header("Authorization") String token,
                                     @Url String next_url);

    @GET
    Observable<ListSimpleUser> getNextSimpleUser(@Header("Authorization") String token,
                                                 @Url String next_url);


    @GET
    Observable<ListIllust> getNextIllust(@Header("Authorization") String token,
                                         @Url String next_url);

    @GET
    Observable<ListNovel> getNextNovel(@Header("Authorization") String token,
                                       @Url String next_url);

    @GET
    Observable<ListNovelOfSeries> getNextSeriesNovel(@Header("Authorization") String token,
                                                     @Url String next_url);

    @GET
    Observable<ListArticle> getNextArticles(@Header("Authorization") String token,
                                            @Url String next_url);


    //https://app-api.pixiv.net/web/v1/login?code_challenge=
    // BpI4XJUk4nHHBwbhTNdunQDhB4Ca0M3yBcC_v7E0lUw&

    @GET("web/v1/login?code_challenge_method=S256&client=pixiv-android")
    Observable<String> tryLogin(@Query("code_challenge") String code_challenge);

    // 添加小说书签 相同id只能有1个 不同页数会直接覆盖
    @FormUrlEncoded
    @POST("v1/novel/marker/add")
    Observable<NullResponse> postAddNovelMarker(@Header("Authorization") String token,
                                           @Field("novel_id") int novel_id,
                                           @Field("page") int page);

    // 删除小说书签
    @FormUrlEncoded
    @POST("v1/novel/marker/delete")
    Observable<NullResponse> postDeleteNovelMarker(@Header("Authorization") String token,
                                                @Field("novel_id") int novel_id);

    // 推荐用户
    @GET("v1/user/related?filter=for_android")
    Observable<ListUser> getRelatedUsers(@Header("Authorization") String token,
                                             @Query("seed_user_id") int seed_user_id);
}
