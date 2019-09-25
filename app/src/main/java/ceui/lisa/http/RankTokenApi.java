package ceui.lisa.http;

import ceui.lisa.model.TempTokenResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;

public interface RankTokenApi {

    @GET("/token")
    Observable<TempTokenResponse> getRankToken();
}
