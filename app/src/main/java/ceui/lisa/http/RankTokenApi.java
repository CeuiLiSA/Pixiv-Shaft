package ceui.lisa.http;

import ceui.lisa.model.TempTokenResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;

public interface RankTokenApi {

    String BASE_URL = "https://s.aragaki.fun/";

    @GET("/token")
    Observable<TempTokenResponse> getRankToken();
}
