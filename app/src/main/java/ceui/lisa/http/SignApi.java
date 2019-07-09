package ceui.lisa.http;

import ceui.lisa.model.SignResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.Header;
import retrofit2.http.POST;

/**
 * 用作新用户注册
 */
public interface SignApi {


    @FormUrlEncoded
    @POST("api/provisional-accounts/create")
    Observable<SignResponse> pixivSign(@Header("Authorization") String token,
                                     @Field("user_name") String userName,
                                     @Field("ref") String ref);


    @FormUrlEncoded
    @POST("index.php/user/index/provisionalAccounts")
    Observable<SignResponse> pixivLiteSign(@Field("user_name") String userName,
                                     @Field("agent") String agent,
                                     @Field("x-client-time") String time,
                                     @Field("x-client-hash") String hash);




}
