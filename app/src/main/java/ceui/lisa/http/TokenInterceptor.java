package ceui.lisa.http;

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
    private static boolean isTokenNew = true;

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        if (isTokenExpired(response)) {
            response.close();
            //标记token为过期token
            isTokenNew = false;
            Common.showLog("getNewToken 标记token为过期token");
            String newToken = getNewToken();
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
     * 同步请求方式，获取最新的Token
     *
     * @return
     */
    private synchronized String getNewToken() throws IOException {
        //如果token确实是过期的，就掉接口获取新的token
        Common.showLog("getNewToken 开始");
        String result;
        if (!isTokenNew) {
            Common.showLog("getNewToken 掉接口获取新的token");
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
            isTokenNew = true;
            Common.showLog("getNewToken 请求token，token刷新成功");
            if (newUser != null && newUser.getResponse() != null) {
                result = newUser.getResponse().getAccess_token();
            } else {
                result = "ERROR ON GET TOKEN";
            }
        } else {
            //如果token已经被上一个调用这个方法的人刷新过了，就直接用sUserModel的getAccess_token
            Common.showLog("getNewToken 不请求token，使用刚更新的token");
            result = Shaft.sUserModel.getResponse().getAccess_token();
        }
        Common.showLog("getNewToken 结束");
        return result;
    }
}
