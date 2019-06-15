package ceui.lisa.http;

import io.reactivex.Observable;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface SauceNaoApi {

    @Multipart
    @POST("/search.php")
    Observable<ResponseBody> query(@Part MultipartBody.Part part);
}
