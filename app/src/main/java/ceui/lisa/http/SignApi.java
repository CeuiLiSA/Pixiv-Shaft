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


    //为啥分成这么多方法？ 因为多提交一个参数就报错，蛋疼

    /**
     * 改密码，改邮箱，改pixiv id
     *
     * @param token
     * @param new_mail_address
     * @param new_user_account
     * @param current_password
     * @param new_password
     * @return
     */
    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> edit(@Header("Authorization") String token,
                                         @Field("new_mail_address") String new_mail_address,
                                         @Field("new_user_account") String new_user_account,
                                         @Field("current_password") String current_password,
                                         @Field("new_password") String new_password);

    /**
     * 只改密码
     *
     * @param token
     * @param current_password
     * @param new_password
     * @return
     */
    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changePassword(@Header("Authorization") String token,
                                                   @Field("current_password") String current_password,
                                                   @Field("new_password") String new_password);

    /**
     * 改密码还改pixivid
     *
     * @param token
     * @param new_user_account
     * @param current_password
     * @param new_password
     * @return
     */
    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changePasswordPixivID(@Header("Authorization") String token,
                                                   @Field("new_user_account") String new_user_account,
                                                   @Field("current_password") String current_password,
                                                   @Field("new_password") String new_password);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changePixivID(@Header("Authorization") String token,
                                                          @Field("new_user_account") String new_user_account,
                                                          @Field("current_password") String current_password);

    /**
     * 只改邮箱
     *
     * @param token
     * @param new_mail_address
     * @param current_password
     * @return
     */
    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changeEmail(@Header("Authorization") String token,
                                                   @Field("new_mail_address") String new_mail_address,
                                                   @Field("current_password") String current_password);

    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changeEmailAndPixivID(@Header("Authorization") String token,
                                                @Field("new_mail_address") String new_mail_address,
                                                @Field("new_user_account") String new_user_account,
                                                @Field("current_password") String current_password);

    /**
     * 改邮箱还改密码
     *
     * @param token
     * @param new_mail_address
     * @param current_password
     * @param new_password
     * @return
     */
    @FormUrlEncoded
    @POST("/api/account/edit")
    Observable<AccountEditResponse> changeEmailAndPassword(@Header("Authorization") String token,
                                                           @Field("new_mail_address") String new_mail_address,
                                                           @Field("current_password") String current_password,
                                                           @Field("new_password") String new_password);
}
