package ceui.lisa.http;

import ceui.lisa.models.AccountEditResponse;
import ceui.lisa.models.SignResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.Header;
import retrofit2.http.POST;

/**
 * 用作新用户注册
 */
public interface SignApi {

    //用作注册账号
    String SIGN_API = "https://accounts.pixiv.net/";

    @FormUrlEncoded
    @POST("api/provisional-accounts/create")
    Observable<SignResponse> pixivSign(@Header("Authorization") String token,
                                       @Field("user_name") String userName,
                                       @Field("ref") String ref);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> edit(@Header("Authorization") String token,
                                         @Field("new_mail_address") String new_mail_address,
                                         @Field("new_user_account") String new_user_account,
                                         @Field("current_password") String current_password,
                                         @Field("new_password") String new_password);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changePassword(@Header("Authorization") String token,
                                                   @Field("current_password") String current_password,
                                                   @Field("new_password") String new_password);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changePasswordAndAddress(@Header("Authorization") String token,
                                                   @Field("new_user_account") String new_user_account,
                                                   @Field("current_password") String current_password,
                                                   @Field("new_password") String new_password);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changeEmail(@Header("Authorization") String token,
                                                   @Field("new_mail_address") String new_mail_address,
                                                   @Field("current_password") String current_password);


    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changeEmailAndAddress(@Header("Authorization") String token,
                                                @Field("new_mail_address") String new_mail_address,
                                                @Field("current_password") String current_password,
                                                @Field("new_password") String new_password);
}
