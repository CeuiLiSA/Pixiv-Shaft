package ceui.lisa.http;

import ceui.lisa.response.RankTokenResponse;
import ceui.lisa.response.SignResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * 用作新用户注册
 */
public interface SignApi {


    @FormUrlEncoded
    @POST("api/provisional-accounts/create?ref=pixiv_android_app_provisional_account")
    Observable<SignResponse> nowSign(@Field("user_name") String userName);


}
