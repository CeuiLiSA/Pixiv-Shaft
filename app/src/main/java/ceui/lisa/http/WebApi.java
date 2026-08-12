package ceui.lisa.http;

import ceui.loxia.UserTagIllustBody;
import ceui.loxia.UserTagNovelBody;
import ceui.loxia.WebResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * www.pixiv.net 网页 ajax 接口(RxJava 版),配合 {@link Retro#getWebApi()} 使用。
 * 走 {@link ceui.loxia.WebHeaderInterceptor} 带上已同步的网页 cookie ——
 * 公开作品无 cookie 也能拿,R-18/私密作品需用户在设置里同步过 cookie。
 */
public interface WebApi {

    /**
     * issue #569: 按 Tag 筛选某画师的插画/漫画作品。offset/limit 翻页,limit 固定 48 跟网页一致。
     * sensitiveFilterMode=userSetting 跟随账号的敏感内容设置。
     * category 取 "illusts" / "manga"(issue #996,两个端点响应完全同构,共用一个方法)。
     */
    @GET("ajax/user/{userId}/{category}/tag")
    Observable<WebResponse<UserTagIllustBody>> getUserIllustsByTag(
            @Path("userId") long userId,
            @Path("category") String category,
            @Query("tag") String tag,
            @Query("offset") int offset,
            @Query("limit") int limit,
            @Query("sensitiveFilterMode") String sensitiveFilterMode,
            @Query("lang") String lang);

    /**
     * issue #996: 按 Tag 筛选某作者的小说。翻页与敏感内容参数同上,
     * works 是小说形状的精简对象(封面/字数/收藏数),见 {@link UserTagNovelBody}。
     */
    @GET("ajax/user/{userId}/novels/tag")
    Observable<WebResponse<UserTagNovelBody>> getUserNovelsByTag(
            @Path("userId") long userId,
            @Query("tag") String tag,
            @Query("offset") int offset,
            @Query("limit") int limit,
            @Query("sensitiveFilterMode") String sensitiveFilterMode,
            @Query("lang") String lang);
}
