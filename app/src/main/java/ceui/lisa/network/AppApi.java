package ceui.lisa.network;

import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.LoginResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface AppApi {

    /**
     * 获取排行榜
     *
     * @param filter
     * @param mode
     * @return
     */
    @FormUrlEncoded
    @GET("/v1/illust/ranking")
    Observable<ListIllustResponse> getRank(@Query("filter") String filter,
                                           @Query("mode") String mode);
}
