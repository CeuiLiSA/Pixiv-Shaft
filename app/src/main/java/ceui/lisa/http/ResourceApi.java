package ceui.lisa.http;

import io.reactivex.Observable;
import okhttp3.ResponseBody;
import retrofit2.http.GET;

public interface ResourceApi {
    String JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/";

    @GET("gh/CeuiLiSA/Pixiv-Shaft@master/app/src/main/assets/comment.filter.rule.txt")
    Observable<ResponseBody> getCommentFilterRule();
}
