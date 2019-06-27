package ceui.lisa.http;

import ceui.lisa.model.ListUserResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Query;

public interface CountApi {


    @GET("/ctr_user_basic/get_realtime_data?app_id=3104085115&idx=10101,10102,10103,10104,10105")
    Observable<ListUserResponse> getRealTimeData(@Query("sign") String sign);
}
