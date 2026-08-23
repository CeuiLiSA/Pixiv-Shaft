package ceui.lisa.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import ceui.lisa.R;
import ceui.loxia.AccountResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.pixiv.actions.PixivActions;
import ceui.pixiv.login.InvalidRefreshTokenException;
import ceui.pixiv.login.PixivLogin;
import ceui.pixiv.login.PixivOAuthResponse;
import ceui.pixiv.session.SessionManager;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * 检测到 400 OAuth 过期时自动用 refresh_token 换新 access_token，并重放原请求。
 * Token 交换走 {@link PixivLogin}（内部使用共享的 OkHttp + Worker relay 配置）。
 */
public class TokenInterceptor implements Interceptor {

    private static final String TOKEN_ERROR_1 = "Error occurred at the OAuth process";
    private static final String TOKEN_ERROR_2 = "Invalid refresh token";
    private static final String EXPLICIT_AUTH_MARKER = "X-Shaft-Explicit-Authorization";

    @NotNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        // Explicitly-authorized search methods own their borrowed-token lifecycle. Strip this
        // internal marker before the network call and never replace their Authorization with the
        // currently logged-in account during an automatic retry.
        boolean explicitAuthorization = "1".equals(originalRequest.header(EXPLICIT_AUTH_MARKER));
        Request request = explicitAuthorization
                ? originalRequest.newBuilder().removeHeader(EXPLICIT_AUTH_MARKER).build()
                : originalRequest;
        Response response = chain.proceed(request);

        if (!explicitAuthorization && isTokenExpired(response)) {
            Timber.i("TokenInterceptor: access_token 过期，正在刷新");
            response.close();
            String newBearer = getNewToken(request.header("Authorization"));
            Request newRequest = request
                    .newBuilder()
                    .header("Authorization", newBearer)
                    .build();
            return chain.proceed(newRequest);
        }
        return response;
    }

    private boolean isTokenExpired(Response response) {
        // 只有 400 才可能是 OAuth 错误；其它状态短路，避免把所有响应体都 buffer 到内存。
        if (response.code() != 400) {
            return false;
        }
        final String body = Common.getResponseBody(response);
        if (body.contains(TOKEN_ERROR_1)) {
            return true;
        }
        if (body.contains(TOKEN_ERROR_2)) {
            logoutAndRestart();
        }
        return false;
    }

    private synchronized String getNewToken(String tokenForThisRequest) throws IOException {
        String currentBearer = SessionManager.INSTANCE.getBearerToken();
        // 如果别的线程已经刷过了（当前缓存 ≠ 本请求头），直接复用，避免重复刷新。
        if (!currentBearer.equals(tokenForThisRequest)) {
            return currentBearer;
        }
        String refreshToken = SessionManager.INSTANCE.getRefreshToken();
        if (refreshToken == null) {
            throw new IOException("refresh_token not exist");
        }
        try {
            PixivOAuthResponse response = PixivLogin.INSTANCE.refreshTokenBlocking(refreshToken);
            AccountResponse cached = Local.getUser();
            if (cached != null) {
                cached.setAccess_token(response.getAccessToken());
                cached.setRefresh_token(response.getRefreshToken());
                cached.setExpires_in(response.getExpiresIn());
                if (cached.getUser() != null) {
                    cached.getUser().set_login(true);
                    // 会员状态**不能**沿用这份 SharedPreferences 副本里的：它是登录那一刻
                    // 写下来的，之后只有 SessionManager(MMKV) 那份会被资料同步修正，这份
                    // 从此再没变过。照抄它上报，就是「会员过期了还报有会员 / 登录后买的会员
                    // 一直报没有」的来源——前者让号留在借号池里，借到的人白花一次额度。
                    cached.getUser().set_premium(SessionManager.INSTANCE.premiumForLegacyStore(
                            response, Boolean.TRUE.equals(cached.getUser().is_premium())));
                }
                long uid = cached.getUser() != null ? cached.getUserId() : 0L;
                PixivActions.bindAccountOnline(uid, cached);
                Local.saveUser(cached);
            } else {
                SessionManager.INSTANCE.applyTokenRefresh(
                        response.getAccessToken(),
                        response.getRefreshToken(),
                        response.getExpiresIn(),
                        SessionManager.INSTANCE.freshPremiumOf(response));
            }
            return "Bearer " + response.getAccessToken();
        } catch (InvalidRefreshTokenException ex) {
            logoutAndRestart();
            throw new IOException("refresh_token revoked", ex);
        } catch (Exception ex) {
            throw new IOException("Token refresh failed", ex);
        }
    }

    private static void logoutAndRestart() {
        SessionManager.INSTANCE.postUpdateSession(null);
        Common.showToast(R.string.string_340);
        Common.restart();
    }
}
