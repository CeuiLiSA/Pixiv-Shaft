package ceui.lisa.http;

import ceui.lisa.response.ArticalResponse;
import ceui.lisa.response.GifResponse;
import ceui.lisa.response.IllustCommentsResponse;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.ListUserResponse;
import ceui.lisa.response.LoginResponse;
import ceui.lisa.response.NullResponse;
import ceui.lisa.response.RankTokenResponse;
import ceui.lisa.response.TempTokenResponse;
import ceui.lisa.response.TrendingtagResponse;
import ceui.lisa.response.UserDetailResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.Url;

public interface RankTokenApi {


    @GET("/")
    Observable<TempTokenResponse> getRankToken();


}
