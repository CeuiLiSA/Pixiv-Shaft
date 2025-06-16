package ceui.lisa.http;

import ceui.lisa.models.UserModel;
import ceui.loxia.AccountResponse;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

public interface AccountTokenApi {

    @FormUrlEncoded
    @POST("/auth/token")
    Call<UserModel> newRefreshToken(@Field("client_id") String client_id,
                                    @Field("client_secret") String client_secret,
                                    @Field("grant_type") String grant_type,
                                    @Field("refresh_token") String refresh_token,
                                    @Field("include_policy") boolean include_policy);

    @FormUrlEncoded
    @POST("/auth/token")
    Call<AccountResponse> newRefreshToken2(@Field("client_id") String client_id,
                                           @Field("client_secret") String client_secret,
                                           @Field("grant_type") String grant_type,
                                           @Field("refresh_token") String refresh_token,
                                           @Field("include_policy") boolean include_policy);

    @FormUrlEncoded
    @POST("/auth/token")
    Call<AccountResponse> newLogin(@Field("client_id") String client_id,
                                   @Field("client_secret") String client_secret,
                                   @Field("grant_type") String grant_type,//authorization_code
                                   @Field("code") String code,//BB5_yxZvE1n3ECFH9KmPQV3Tu3pfaJqUp-5fuWP-msg
                                   @Field("code_verifier") String code_verifier,//cwnuOPjfkM1f65Cqaf94Pu4EqFNZJcAzfDGKmrAr0vQ
                                   @Field("redirect_uri") String redirect_uri, //https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback
                                   @Field("include_policy") boolean include_policy
    );
}

