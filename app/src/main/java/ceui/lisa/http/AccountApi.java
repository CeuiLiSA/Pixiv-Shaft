package ceui.lisa.http;

import ceui.lisa.models.UserModel;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface AccountApi {

    //用作登录，刷新token
    String ACCOUNT_BASE_URL = "https://oauth.secure.pixiv.net/";

    @FormUrlEncoded
    @POST("/auth/token")
    Observable<UserModel> newLogin(@Field("client_id") String client_id,
                                   @Field("client_secret") String client_secret,
                                   @Field("grant_type") String grant_type,//authorization_code
                                   @Field("code") String code,//BB5_yxZvE1n3ECFH9KmPQV3Tu3pfaJqUp-5fuWP-msg
                                   @Field("code_verifier") String code_verifier,//cwnuOPjfkM1f65Cqaf94Pu4EqFNZJcAzfDGKmrAr0vQ
                                   @Field("redirect_uri") String redirect_uri, //https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback
                                   @Field("include_policy") boolean include_policy
    );

    @GET("login?prompt=select_account&source=pixiv-android&ref=&client=pixiv-android")
    Observable<String> tryLogin(@Query("return_to") String return_to);
}
