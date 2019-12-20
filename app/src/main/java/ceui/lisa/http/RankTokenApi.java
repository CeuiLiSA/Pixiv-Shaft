package ceui.lisa.http;

import ceui.lisa.model.TempTokenResponse;
import io.reactivex.Observable;
import okhttp3.MultipartBody;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface RankTokenApi {

    String BASE_URL = "https://s.aragaki.fun/";

    @GET("/token")
    Observable<TempTokenResponse> getRankToken();



    @Multipart
    @POST("/upload")
    Observable<String> uploadImage(@Part MultipartBody.Part file);
}
