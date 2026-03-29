package ceui.lisa.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.pixiv.session.SessionManager;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

/**
 * 全局自动刷新Token的拦截器
 */
public class TokenInterceptor implements Interceptor {

    private static final String TOKEN_ERROR_1 = "Error occurred at the OAuth process";
    private static final String TOKEN_ERROR_2 = "Invalid refresh token";
    private static final int TOKEN_LENGTH = 50;

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        if (isTokenExpired(response)) {
            Common.showLog("getNewToken 检测到是过期Token ");
            response.close();
            String newToken = getNewToken(request.header("Authorization"));
            Request newRequest = chain.request()
                    .newBuilder()
                    .header("Authorization", newToken)
                    .build();
            return chain.proceed(newRequest);
        }
        return response;
    }

    private boolean isTokenExpired(Response response) {
        final String body = Common.getResponseBody(response);
        Common.showLog("isTokenExpired body " + body);
        if (response.code() == 400) {
            if (body.contains(TOKEN_ERROR_1)) {
                Common.showLog("isTokenExpired 000");
                return true;
            } else if(body.contains(TOKEN_ERROR_2)){
                SessionManager.INSTANCE.postUpdateSession(null);
                Common.showToast(R.string.string_340);
                Common.restart();
                Common.showLog("isTokenExpired 111");
                return false;
            } else {
                Common.showLog("isTokenExpired 222");
                return false;
            }
        } else {
            Common.showLog("isTokenExpired 333");
            return false;
        }
    }

    private synchronized String getNewToken(String tokenForThisRequest) throws IOException {
        String currentBearerToken = SessionManager.INSTANCE.getBearerToken();
        if (currentBearerToken.equals(tokenForThisRequest) ||
                tokenForThisRequest.length() != TOKEN_LENGTH ||
                currentBearerToken.length() != TOKEN_LENGTH) {
            Common.showLog("getNewToken 主动获取最新的token old:" + tokenForThisRequest + " new:" + currentBearerToken);
            String refreshToken = SessionManager.INSTANCE.getRefreshToken();
            if (refreshToken == null) {
                throw new IOException("refresh_token not exist");
            }
            Call<UserModel> call = Retro.getAccountTokenApi().newRefreshToken(
                    FragmentLogin.CLIENT_ID,
                    FragmentLogin.CLIENT_SECRET,
                    FragmentLogin.REFRESH_TOKEN,
                    refreshToken,
                    Boolean.TRUE);
            UserModel newUser = call.execute().body();
            if (newUser != null) {
                newUser.getUser().setIs_login(true);
            }
            Local.saveUser(newUser);
            String newBearerToken = SessionManager.INSTANCE.getBearerToken();
            Common.showLog("getNewToken 获取到了最新的 token:" + newBearerToken);
            return newBearerToken;
        } else {
            Common.showLog("getNewToken 使用最新的token old:" + tokenForThisRequest + " new:" + currentBearerToken);
            return currentBearerToken;
        }
    }
}
