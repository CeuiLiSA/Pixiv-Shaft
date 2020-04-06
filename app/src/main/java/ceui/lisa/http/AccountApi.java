package ceui.lisa.http;

import ceui.lisa.models.AccountEditResponse;
import ceui.lisa.models.UserModel;
import io.reactivex.Observable;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

public interface AccountApi {

    //用作登录，刷新token
    String ACCOUNT_BASE_URL = "https://oauth.secure.pixiv.net/";

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
    Observable<UserModel> login(@Field("client_id") String client_id,
                                @Field("client_secret") String client_secret,
                                @Field("device_token") String device_token,
                                @Field("get_secure_url") boolean get_secure_url,
                                @Field("grant_type") String grant_type,
                                @Field("include_policy") boolean include_policy,
                                @Field("password") String password,
                                @Field("username") String username);

    /**
     * 刷新TOKEN
     *
     * @param client_id
     * @param client_secret
     * @param grant_type
     * @param refresh_token
     * @param device_token
     * @param get_secure_url
     * @param include_policy
     * @return
     */
    @FormUrlEncoded
    @POST("/auth/token")
    Call<UserModel> refreshToken(@Field("client_id") String client_id,
                                 @Field("client_secret") String client_secret,
                                 @Field("grant_type") String grant_type,
                                 @Field("refresh_token") String refresh_token,
                                 @Field("device_token") String device_token,
                                 @Field("get_secure_url") boolean get_secure_url,
                                 @Field("include_policy") boolean include_policy);

}
