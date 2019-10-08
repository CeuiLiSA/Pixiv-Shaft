package ceui.lisa.http;

import ceui.lisa.model.HitoResponse;
import ceui.lisa.model.SignResponse;
import io.reactivex.Observable;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;


public interface HitoApi {

    @GET("/")
    Observable<HitoResponse> getHito();

}
