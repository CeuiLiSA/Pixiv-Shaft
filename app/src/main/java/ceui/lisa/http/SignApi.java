package ceui.lisa.http;

import ceui.lisa.response.RankTokenResponse;
import ceui.lisa.response.SignResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * 用作新用户注册
 */
public interface SignApi {


    @FormUrlEncoded
    @POST("api/provisional-accounts/create")
    Observable<SignResponse> nowSign(@Header("Authorization") String token,
                                     @Field("user_name") String userName,
                                     @Field("ref") String ref);


}
