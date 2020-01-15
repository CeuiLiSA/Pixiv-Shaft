package ceui.lisa.http;

import android.util.Log;

import java.io.IOException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.fragments.FragmentL;
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


    public static boolean isTokenNew = true;
    private static final String TOKEN_ERROR = "Error occurred at the OAuth process";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        if (isTokenExpired(response)) {
            response.close();
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
        if (response.code() == 400 &&
                Common.getResponseBody(response).contains(TOKEN_ERROR)) {
            isTokenNew = false;
            return true;
        } else {
            isTokenNew = true;
            return false;
        }
    }

    /**
     * 同步请求方式，获取最新的Token
     *
     * @return
     */
    private String getNewToken() throws IOException {
        UserModel userModel = Local.getUser();
        Call<UserModel> call = Retro.getAccountApi().refreshToken(
                FragmentL.CLIENT_ID,
                FragmentL.CLIENT_SECRET,
                "refresh_token",
                userModel.getResponse().getRefresh_token(),
                userModel.getResponse().getDevice_token(),
                true,
                true);
        UserModel newUser = call.execute().body();
        if (newUser != null) {
            newUser.getResponse().getUser().setPassword(
                    Shaft.sUserModel.getResponse().getUser().getPassword()
            );
        }
        Local.saveUser(newUser);
        isTokenNew = true;
        if (newUser != null && newUser.getResponse() != null) {
            return newUser.getResponse().getAccess_token();
        } else {
            return "ERROR ON GET TOKEN";
        }
    }
}
