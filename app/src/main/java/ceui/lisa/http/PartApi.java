package ceui.lisa.http;

import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.PartCompleteWords;
import ceui.lisa.model.PartResponse;
import ceui.lisa.model.TempTokenResponse;
import ceui.lisa.model.TrendingtagResponse;
import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * 工作项目DEMO
 */
public interface PartApi {


    @GET("es-test/test.php?method=searchparts")
    Observable<PartResponse> searchPart(@Query("keyword") String keyword);

    @GET("es-test/test.php?method=suggestparts")
    Observable<PartCompleteWords> inputHelp(@Query("keyword") String keyword);
}
