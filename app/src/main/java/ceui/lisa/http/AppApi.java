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
import ceui.lisa.model.ListNovelMarkers;
import ceui.lisa.model.ListNovelOfSeries;
import ceui.lisa.model.ListNovelSeries;
import ceui.lisa.model.ListSimpleUser;
import ceui.lisa.model.ListTag;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.model.ListUser;
import ceui.lisa.model.ListWatchlistManga;
import ceui.lisa.model.ListWatchlistNovel;
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
     * @param mode The type of rank:day/week/.../
     * @return ListIllust Observable<ListIllust>{@link ListIllust}
     */
    @GET("v1/illust/ranking?filter=for_android")
    Observable<ListIllust> getRank(@Query("mode") String mode,
                                   @Query("date") String date);
    @GET("v1/novel/ranking?filter=for_android")
    Observable<ListNovel> getRankNovel(@Query("mode") String mode,
                                       @Query("date") String date);

    /**
     * 推荐榜单
     * @param include_ranking_illusts (indoubt)
     * @return RecmdIllust Observable<RecmdIllust>{@link RecmdIllust}
     */
    @GET("v1/illust/recommended?include_privacy_policy=true&filter=for_android")
    Observable<RecmdIllust> getRecmdIllust(@Query("include_ranking_illusts") boolean include_ranking_illusts);


    @GET("v1/manga/recommended?include_privacy_policy=true&filter=for_android&include_ranking_illusts=true")
    Observable<RecmdIllust> getRecmdManga();

    @GET("v1/novel/recommended?include_privacy_policy=true&filter=for_android&include_ranking_novels=true")
    Observable<ListNovel> getRecmdNovel();

    @GET("v1/novel/follow")
    Observable<ListNovel> getBookedUserSubmitNovel(@Query("restrict") String restrict);


    @GET("v1/trending-tags/{type}?filter=for_android&include_translated_tag_results=true")
    Observable<ListTrendingtag> getHotTags(@Path("type") String type);


    /**
     * 原版app登录时候的背景墙
     *
     * @return
     */
    @GET("v1/walkthrough/illusts?filter=for_android")
    Observable<ListIllust> getLoginBg();


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
    Observable<ListIllust> searchIllust(@Query("word") String word,
                                        @Query("sort") String sort,
                                        @Query("start_date") String startDate,
                                        @Query("end_date") String endDate,
                                        @Query("search_target") String search_target);

    /**
     * search_target=exact_match_for_tags,partial_match_for_tags,text(文本),keyword(关键词)
     */
    @GET("v1/search/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListNovel> searchNovel(@Query("word") String word,
                                      @Query("sort") String sort,
                                      @Query("start_date") String startDate,
                                      @Query("end_date") String endDate,
                                      @Query("search_target") String search_target);


    @GET("v2/illust/related?filter=for_android")
    Observable<ListIllust> relatedIllust(@Query("illust_id") int illust_id);


    /**
     * 推荐用户
     *
     * @return
     */
    @GET("v1/user/recommended?filter=for_android")
    Observable<ListUser> getRecmdUser();


    @GET("v1/user/bookmarks/illust")
    Observable<ListIllust> getUserLikeIllust(@Query("user_id") int user_id,
                                             @Query("restrict") String restrict,
                                             @Query("tag") String tag);

    @GET("v1/user/bookmarks/illust")
    Observable<ListIllust> getUserLikeIllust(@Query("user_id") int user_id,
                                             @Query("restrict") String restrict);

    @GET("v1/user/bookmarks/novel")
    Observable<ListNovel> getUserLikeNovel(@Query("user_id") int user_id,
                                           @Query("restrict") String restrict,
                                           @Query("tag") String tag);

    @GET("v1/user/bookmarks/novel")
    Observable<ListNovel> getUserLikeNovel(@Query("user_id") int user_id,
                                           @Query("restrict") String restrict);

    @GET("v1/user/illusts?filter=for_android")
    Observable<ListIllust> getUserSubmitIllust(@Query("user_id") int user_id,
                                               @Query("type") String type);

    @GET("v1/user/novels")
    Observable<ListNovel> getUserSubmitNovel(@Query("user_id") int user_id);


    @GET("v2/illust/follow")
    Observable<ListIllust> getFollowUserIllust(@Query("restrict") String restrict);


    @GET("v1/spotlight/articles?filter=for_android")
    Observable<ListArticle> getArticles(@Query("category") String category);


    ///v1/user/detail?filter=for_android&user_id=24218478
    @GET("v1/user/detail?filter=for_android")
    Observable<UserDetailResponse> getUserDetail(@Query("user_id") int user_id);


    //  /v1/ugoira/metadata?illust_id=47297805
    @GET("v1/ugoira/metadata")
    Observable<GifResponse> getGifPackage(@Query("illust_id") int illust_id);


    @FormUrlEncoded
    @POST("v1/user/follow/add")
    Observable<NullResponse> postFollow(@Field("user_id") int user_id,
                                        @Field("restrict") String followType);

    @FormUrlEncoded
    @POST("v1/user/follow/delete")
    Observable<NullResponse> postUnFollow(@Field("user_id") int user_id);

    @GET("v1/user/follow/detail")
    Observable<UserFollowDetail> getFollowDetail(@Query("user_id") int user_id);


    /**
     * 获取userid 所关注的人
     *
     * @param user_id
     * @param restrict
     * @return
     */
    @GET("v1/user/following?filter=for_android")
    Observable<ListUser> getFollowUser(@Query("user_id") int user_id,
                                       @Query("restrict") String restrict);


    //获取关注 这个userid 的人
    @GET("v1/user/follower?filter=for_android")
    Observable<ListUser> getWhoFollowThisUser(@Query("user_id") int user_id);


    @GET("/v3/illust/comments")
    Observable<ListComment> getIllustComment(@Query("illust_id") int illust_id);

    @GET("v3/novel/comments")
    Observable<ListComment> getNovelComment(@Query("novel_id") int novel_id);

    @GET
    Observable<ListComment> getNextComment(@Url String nextUrl);


    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    Observable<CommentHolder> postIllustComment(@Field("illust_id") int illust_id,
                                                @Field("comment") String comment);

    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    Observable<CommentHolder> postIllustComment(@Field("illust_id") int illust_id,
                                                @Field("comment") String comment,
                                                @Field("parent_comment_id") int parent_comment_id);

    @FormUrlEncoded
    @POST("v1/novel/comment/add")
    Observable<CommentHolder> postNovelComment(@Field("novel_id") int novel_id,
                                          @Field("comment") String comment);

    @FormUrlEncoded
    @POST("v1/novel/comment/add")
    Observable<CommentHolder> postNovelComment(@Field("novel_id") int novel_id,
                                          @Field("comment") String comment,
                                          @Field("parent_comment_id") int parent_comment_id);

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    Observable<NullResponse> postLikeIllust(@Field("illust_id") int illust_id,
                                            @Field("restrict") String restrict);

    @FormUrlEncoded
    @POST("v2/novel/bookmark/add")
    Observable<NullResponse> postLikeNovel(@Field("novel_id") int novel_id,
                                           @Field("restrict") String restrict);

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    Observable<NullResponse> postLikeIllustWithTags(@Field("illust_id") int illust_id,
                                                    @Field("restrict") String restrict,
                                                    @Field("tags[]") String... tags);

    @FormUrlEncoded
    @POST("v2/novel/bookmark/add")
    Observable<NullResponse> postLikeNovelWithTags(@Field("novel_id") int novel_id,
                                                    @Field("restrict") String restrict,
                                                    @Field("tags[]") String... tags);

    @FormUrlEncoded
    @POST("v1/illust/bookmark/delete")
    Observable<NullResponse> postDislikeIllust(@Field("illust_id") int illust_id);

    @FormUrlEncoded
    @POST("v1/novel/bookmark/delete")
    Observable<NullResponse> postDislikeNovel(@Field("novel_id") int novel_id);


    @GET("v1/illust/detail?filter=for_android")
    Observable<IllustSearchResponse> getIllustByID(@Query("illust_id") long illust_id);


    @GET("v1/search/user?filter=for_android")
    Observable<ListUser> searchUser(@Query("word") String word);


    @GET("v1/search/popular-preview/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListIllust> popularPreview(@Query("word") String word,
                                          @Query("start_date") String startDate,
                                          @Query("end_date") String endDate,
                                          @Query("search_target") String search_target);

    @GET("v1/search/popular-preview/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    Observable<ListNovel> popularNovelPreview(@Query("word") String word,
                                          @Query("start_date") String startDate,
                                          @Query("end_date") String endDate,
                                          @Query("search_target") String search_target);


    /**
     * (In doubt)
     * Search by key word
     * <p>
     *     For example:
     * </p>
     * <p>
     *     "洛天依" in Chinese
     * </p>
     * <p>
     *     "\u6d1b\u5929\u4f9d" in Unicode
     * </p>
     * <p>
     *     "%E6%B4%9B%E5%A4%A9%E4%BE%9D" in URL
     * </p>
     * <p>
     *     URL:"https://app-api.pixiv.net/v2/search/autocomplete?merge_plain_keyword_results=true&word=%E6%B4%9B%E5%A4%A9%E4%BE%9D"
     * </p>
     *
     * @return {"tags":[{"name":"\u6d1b\u5929\u4f9d","translated_name":null}]}
     *
     */
    // v2/search/autocomplete?merge_plain_keyword_results=true&word=%E5%A5%B3%E4%BD%93 HTTP/1.1
    @GET("v2/search/autocomplete?merge_plain_keyword_results=true")
    Observable<ListTrendingtag> searchCompleteWord(@Query("word") String word);


    /**
     * 获取所有插画收藏的标签
     */
    //GET v1/user/bookmark-tags/illust?user_id=41531382&restrict=public HTTP/1.1
    @GET("v1/user/bookmark-tags/illust")
    Observable<ListTag> getAllIllustBookmarkTags(@Query("user_id") int user_id,
                                                 @Query("restrict") String restrict);

    /**
     * 获取所有小说收藏的标签
     */
    //GET v1/user/bookmark-tags/novel?user_id=41531382&restrict=public HTTP/1.1
    @GET("v1/user/bookmark-tags/novel")
    Observable<ListTag> getAllNovelBookmarkTags(@Query("user_id") int user_id,
                                                @Query("restrict") String restrict);


    @GET
    Observable<ListTag> getNextTags(@Url String nextUrl);

    /**
     * 获取单个插画收藏的标签
     */
    @GET("v2/illust/bookmark/detail")
    Observable<ListBookmarkTag> getIllustBookmarkTags(@Query("illust_id") int illust_id);
    /**
     * 获取单个小说收藏的标签
     */
    @GET("v2/novel/bookmark/detail")
    Observable<ListBookmarkTag> getNovelBookmarkTags(@Query("novel_id") int novel_id);


    /**
     * 获取已屏蔽的标签/用户
     * <p>
     * 这功能感觉做了没啥卵用，因为未开会员的用户只能屏蔽一个标签/用户，
     * <p>
     * 你屏蔽了一个用户，就不能再屏蔽标签，屏蔽了标签，就不能屏蔽用户，而且都只能屏蔽一个，擦
     *
     * @return
     */
    @GET("v1/mute/list")
    Observable<MutedHistory> getMutedHistory();


    //获取好P友
    @GET("v1/user/mypixiv?filter=for_android")
    Observable<ListUser> getNiceFriend(@Query("user_id") int user_id);

    //获取最新作品
    @GET("v1/illust/new?filter=for_android")
    Observable<ListIllust> getNewWorks(@Query("content_type") String content_type);

    //获取最新作品
    @GET("v1/novel/new")
    Observable<ListNovel> getNewNovels();


    @GET("/webview/v2/novel")
    Call<ResponseBody> getNovelDetailV2(@Query("id") long id);


    //获取好P友
    @GET("v1/user/me/state")
    Observable<UserState> getAccountState();

    @Multipart
    @POST("v1/user/profile/edit")
    Observable<NullResponse> updateUserProfile(@Part List<MultipartBody.Part> parts);


    @GET("v1/live/list")
    Observable<ListLive> getLiveList(@Query("list_type") String list_type);

    @GET("v1/illust/bookmark/users?filter=for_android")
    Observable<ListSimpleUser> getUsersWhoLikeThisIllust(@Query("illust_id") int illust_id);

    @GET("v2/novel/series")
    Observable<ListNovelOfSeries> getNovelSeries(@Query("series_id") int series_id);

    @GET("v2/novel/detail")
    Observable<NovelSearchResponse> getNovelByID(@Query("novel_id") long novel_id);

    @GET("v1/illust/series?filter=for_android")
    Observable<ListMangaOfSeries> getMangaSeriesById(@Query("illust_series_id") int illust_series_id);


    @GET("v1/user/illust-series")
    Observable<ListMangaSeries> getUserMangaSeries(@Query("user_id") int user_id);


    @GET("v1/user/novel-series")
    Observable<ListNovelSeries> getUserNovelSeries(@Query("user_id") int user_id);

    @FormUrlEncoded
    @POST("v1/user/workspace/edit")
    Observable<NullResponse> editWorkSpace(@FieldMap HashMap<String, String> fields);


    @GET("v1/user/profile/presets")
    Observable<Preset> getPresets();

    @GET("v2/illust/mypixiv")
    Observable<ListIllust> getNiceFriendIllust();

    @GET("v1/novel/mypixiv")
    Observable<ListNovel> getNiceFriendNovel();


    @GET
    Observable<ListNovelSeries> getNextUserNovelSeries(@Url String next_url);

    @GET
    Observable<ListMangaSeries> getNextUserMangaSeries(@Url String next_url);

    @GET
    Observable<ListUser> getNextUser(@Url String next_url);

    @GET
    Observable<ListSimpleUser> getNextSimpleUser(@Url String next_url);


    @GET
    Observable<ListIllust> getNextIllust(@Url String next_url);

    @GET
    Observable<ListNovel> getNextNovel(@Url String next_url);

    @GET
    Observable<ListNovelOfSeries> getNextSeriesNovel(@Url String next_url);

    @GET
    Observable<ListArticle> getNextArticles(@Url String next_url);


    // 添加小说书签 相同id只能有1个 不同页数会直接覆盖
    @FormUrlEncoded
    @POST("v1/novel/marker/add")
    Observable<NullResponse> postAddNovelMarker(@Field("novel_id") int novel_id,
                                           @Field("page") int page);

    // 删除小说书签
    @FormUrlEncoded
    @POST("v1/novel/marker/delete")
    Observable<NullResponse> postDeleteNovelMarker(@Field("novel_id") int novel_id);

    // 推荐用户
    @GET("v1/user/related?filter=for_android")
    Observable<ListUser> getRelatedUsers(@Query("seed_user_id") int seed_user_id);

    // 小说追更列表
    @GET("v1/watchlist/novel")
    Observable<ListWatchlistNovel> getWatchlistNovel();

    @GET
    Observable<ListWatchlistNovel> getNextWatchlistNovel(@Url String next_url);

    // 加入/取消追更小说
    @FormUrlEncoded
    @POST("v1/watchlist/novel/add")
    Observable<NullResponse> postWatchlistNovelAdd(@Field("series_id") int series_id);

    @FormUrlEncoded
    @POST("v1/watchlist/novel/delete")
    Observable<NullResponse> postWatchlistNovelDelete(@Field("series_id") int series_id);

    // 漫画追更列表
    @GET("v1/watchlist/manga")
    Observable<ListWatchlistManga> getWatchlistManga();

    @GET
    Observable<ListWatchlistManga> getNextWatchlistManga(@Url String next_url);

    // 加入/取消追更漫画
    @FormUrlEncoded
    @POST("v1/watchlist/manga/add")
    Observable<NullResponse> postWatchlistMangaAdd(@Field("series_id") int series_id);

    @FormUrlEncoded
    @POST("v1/watchlist/manga/delete")
    Observable<NullResponse> postWatchlistMangaDelete(@Field("series_id") int series_id);

    // 小说书签
    @GET("v2/novel/markers")
    Observable<ListNovelMarkers> getNovelMarkers();

    @GET
    Observable<ListNovelMarkers> getNextNovelMarkers(@Url String next_url);
}
