package ceui.lisa.http;

import ceui.lisa.model.ListIllust;
import io.reactivex.Observable;
import okhttp3.Cookie;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Query;

public interface LofterApi {
    /**
     * 'https://api.lofter.com/oldapi/post/detail.api?product=lofter-android-7.3.4&targetblogid=' + str(
     *         targetblogid) + '&supportposttypes=1,2,3,4,5,6&offset=0&postdigestnew=1&postid=' + str(postid) + '&blogId=' + str(
     *         targetblogid) + '&checkpwd=1&needgetpoststat=1'
     */
    String LOFTER_BASE_URL = "https://api.lofter.com/oldapi/";
    String LOFTER_HEADER = "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.20 Safari/537.36";
    String LOFTER_APICOOKIE = "usertrack=dZPgEWPiVMNF6YfwJEIHAg==; NEWTOKEN=ZGUyN2NjOTE1YzE2ZmIwOTM0ZGU5MTIwYjJkZjBhNDJkMDI3YTliNGE4M2ZhMjkxYmY3ODZkN2VkNWRhZTBkNDE1Y2NkNDg4ZDUyMDAzZWNmNWUyMjgwNWY5NTQ2MGZm; NTESwebSI=DFD9B345542ECECF843D7DC7D99313F2.lofter-tomcat-docker-lftpro-3-avkys-cd6be-774f69457-rggg6-8080";


    @GET("v1/illust/ranking?filter=for_android")
    Observable<ListIllust> getLofterRank(@Header("User-Agent") String LOFTER_HEADER,
                                         @Cookie("Cookie") String LOFTER_APICOOKIE);
}
