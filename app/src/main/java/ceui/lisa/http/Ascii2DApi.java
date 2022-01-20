package ceui.lisa.http;

import io.reactivex.Observable;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Response;
import retrofit2.http.Headers;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface Ascii2DApi {
    @Multipart
    @POST("/search/file")
    @Headers("User-Agent: RetrofitClient")
    Observable<Response<ResponseBody>> query(@Part MultipartBody.Part part);
}
