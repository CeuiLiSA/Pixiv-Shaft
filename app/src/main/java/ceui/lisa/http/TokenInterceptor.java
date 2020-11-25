package ceui.lisa.http;

import android.text.TextUtils;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

/**
 * 全局自动刷新Token的拦截器
 */
public class TokenInterceptor implements Interceptor {

    private static final String TOKEN_ERROR = "Error occurred at the OAuth process";
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


    /**
     * 根据Response，判断Token是否失效
     *
     * @param response
     * @return
     */
    private boolean isTokenExpired(Response response) {
        return response.code() == 400 &&
                Common.getResponseBody(response).contains(TOKEN_ERROR);
    }

    /**
     * 同步请求方式，获取最新的Token，解决多并发请求多次刷新token的问题
     *
     * @return
     */
    private synchronized String getNewToken(String tokenForThisRequest) throws IOException {
        if (Shaft.sUserModel.getResponse().getAccess_token().equals(tokenForThisRequest) ||
                tokenForThisRequest.length() != TOKEN_LENGTH ||
                Shaft.sUserModel.getResponse().getAccess_token().length() != TOKEN_LENGTH) {
            Common.showLog("getNewToken 主动获取最新的token old:" + tokenForThisRequest + " new:" + Shaft.sUserModel.getResponse().getAccess_token());
            UserModel userModel = Local.getUser();
            Call<UserModel> call = Retro.getAccountApi().refreshToken(
                    FragmentLogin.CLIENT_ID,
                    FragmentLogin.CLIENT_SECRET,
                    FragmentLogin.REFRESH_TOKEN,
                    userModel.getResponse().getRefresh_token(),
                    userModel.getResponse().getDevice_token(),
                    Boolean.TRUE,
                    Boolean.TRUE);
            UserModel newUser = call.execute().body();
            if (newUser != null) {
                newUser.getResponse().getUser().setPassword(
                        Shaft.sUserModel.getResponse().getUser().getPassword()
                );
                newUser.getResponse().getUser().setIs_login(true);
            }
            Local.saveUser(newUser);
            Common.showLog("getNewToken 获取到了最新的 token:" + newUser.getResponse().getAccess_token());
            return newUser.getResponse().getAccess_token();
        } else {
            Common.showLog("getNewToken 使用最新的token old:" + tokenForThisRequest + " new:" + Shaft.sUserModel.getResponse().getAccess_token());
            return Shaft.sUserModel.getResponse().getAccess_token();
        }
    }
}
