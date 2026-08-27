package ceui.lisa.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.pixiv.session.SessionManager;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * 检测到 400 OAuth 过期时自动用 refresh_token 换新 access_token，并重放原请求。
 * Token 交换委托 {@link SessionManager#refreshAccessToken(String)}，与 Client.kt 栈共用同一把单飞锁。
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
            // 未登录 / 刚被登出时不去刷新：没有会话可刷，而 SessionManager.getAccessToken()
            // 在没有账号时抛的是 RuntimeException —— okhttp 的 AsyncCall.run 只把 IOException
            // 当作请求失败，其余 Throwable 会被重新抛出，直接炸掉 Dispatcher 线程。
            // 匿名请求同样会收到 400 "Error occurred at the OAuth process"，走的就是这里。
            // 服务端的 400 原样交回上层（getResponseBody 只 peek 不消费，body 仍可读），
            // 比抛 IOException 更好：后者会被 toAppError 归成「网络不可用」，盖掉真实原因。
            if (!SessionManager.INSTANCE.isLoggedIn()) {
                Timber.w("Authentication failed without an active session; skipping refresh");
                return response;
            }
            String tokenForThisRequest = stripBearer(request.header("Authorization"));
            Timber.tag("TokenRefresh").d("[%s] 400 token error on %s %s (Retro stack) → asking for refresh",
                    Thread.currentThread().getName(), request.method(), request.url().encodedPath());
            // 刷新统一走 SessionManager 的单飞锁：Client.kt 那套栈（TokenFetcherInterceptor）
            // 也是它。两套 OkHttp 栈并发 400 时只能有一个线程拿 refresh_token 去换——
            // pixiv 会轮换 refresh_token，各刷各的会让输家被判「凭证吊销」强制登出。
            String newAccessToken = SessionManager.INSTANCE.refreshAccessToken(tokenForThisRequest);
            if (newAccessToken == null) {
                // 拿不到新 token（网络失败 / 已登出）：原 400 原样交回上层。别提前 close，
                // Retrofit 还要读 errorBody。
                Timber.tag("TokenRefresh").w("[%s] no refreshed token for %s %s (Retro stack) → returning original 400",
                        Thread.currentThread().getName(), request.method(), request.url().encodedPath());
                return response;
            }
            Timber.tag("TokenRefresh").d("[%s] replaying %s %s (Retro stack) with refreshed token",
                    Thread.currentThread().getName(), request.method(), request.url().encodedPath());
            response.close();
            Request newRequest = request
                    .newBuilder()
                    .header("Authorization", "Bearer " + newAccessToken)
                    .build();
            return chain.proceed(newRequest);
        }
        return response;
    }

    private static String stripBearer(String header) {
        if (header == null) return "";
        return header.startsWith("Bearer ") ? header.substring("Bearer ".length()) : header;
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

    private static void logoutAndRestart() {
        SessionManager.INSTANCE.postUpdateSession(null);
        Common.showToast(R.string.string_340);
        Common.restart();
    }
}
