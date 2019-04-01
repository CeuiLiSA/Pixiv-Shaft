package ceui.lisa.network;

import ceui.lisa.response.LoginResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

public interface AccountApi {


    /**
     * 用户登录
     *
     * @param client_id
     * @param client_secret
     * @param device_token
     * @param get_secure_url
     * @param grant_type
     * @param include_policy
     * @param password
     * @param username
     * @return
     */
    @FormUrlEncoded
    @POST("/auth/token")
    Observable<LoginResponse> login(@Field("client_id") String client_id,
                                    @Field("client_secret") String client_secret,
                                    @Field("device_token") String device_token,
                                    @Field("get_secure_url") String get_secure_url,
                                    @Field("grant_type") String grant_type,
                                    @Field("include_policy") String include_policy,
                                    @Field("password") String password,
                                    @Field("username") String username);
}
