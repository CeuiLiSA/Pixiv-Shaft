package ceui.lisa.network;

import ceui.lisa.response.ListIllustResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
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
    @POST("/v1/illust/ranking")
    Observable<ListIllustResponse> getRank(@Header("Authorization") String token,
                                           @Field("filter") String filter,
                                           @Field("mode") String mode);
}
