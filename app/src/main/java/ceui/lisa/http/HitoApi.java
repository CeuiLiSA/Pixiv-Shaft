package ceui.lisa.http;

import ceui.lisa.model.HitoResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;


public interface HitoApi {

    @GET("/")
    Observable<HitoResponse> getHito();

}
