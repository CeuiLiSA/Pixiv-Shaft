package ceui.lisa.http;

import ceui.lisa.model.HitoResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;


public interface HitoApi {

    //一言API
    String BASE_URL = "https://v1.hitokoto.cn/";

    @GET("/")
    Observable<HitoResponse> getHito();

}
