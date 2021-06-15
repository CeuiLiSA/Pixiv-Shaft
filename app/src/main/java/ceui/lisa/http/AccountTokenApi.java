package ceui.lisa.http;

import ceui.lisa.models.UserModel;
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
}
