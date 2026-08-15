package ceui.lisa.http;

import java.io.IOException;

import ceui.lisa.activities.Shaft;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * App API 代理拦截器（PxveAPI 风格）。
 *
 * <p>将 App API / OAuth 请求改写为 PxveAPI 约定的代理路径：
 * <pre>
 *   https://app-api.pixiv.net/v1/...        →  https://&lt;proxy&gt;/pixiv-app-api/v1/...
 *   https://oauth.secure.pixiv.net/auth/... →  https://&lt;proxy&gt;/pixiv-oauth/auth/...
 * </pre>
 * 由 PxveAPI（或任何实现相同路径约定的服务）反向转发到 Pixiv 官方 App API，
 * 同时透传 Authorization / X-Client-Time / X-Client-Hash 等鉴权头。
 *
 * <p>与 {@link CronetInterceptor} 互斥：开启本代理时直连模式不生效
 * （由 Retro#buildRetrofit / PixivLogin#buildClient 装配时判断）。
 * 图片域名 i.pximg.net / s.pximg.net 不受影响，仍走 ImageHostManager + 图片直连。
 *
 * <p>因为是 OkHttp 拦截器而非改 Retrofit baseUrl，分页接口的
 * {@code @Url String next_url}（Pixiv 返回的绝对 app-api 地址）也会被统一改写，
 * 不会绕过代理。
 */
public class AppApiProxyInterceptor implements Interceptor {

    private static final String APP_API_HOST = "app-api.pixiv.net";
    private static final String OAUTH_HOST = "oauth.secure.pixiv.net";

    @Override
    public Response intercept(Chain chain) throws IOException {
        final Request request = chain.request();
        final HttpUrl url = request.url();
        final String host = url.host();

        final boolean enabled = Shaft.sSettings != null && Shaft.sSettings.isUseAppApiProxy();
        if (!enabled) return chain.proceed(request);

        final String proxy = Shaft.sSettings != null ? Shaft.sSettings.getAppApiProxy() : "";
        if (proxy == null || proxy.trim().isEmpty()) return chain.proceed(request);

        final String prefix;
        if (APP_API_HOST.equals(host)) {
            prefix = "/pixiv-app-api";
        } else if (OAUTH_HOST.equals(host)) {
            prefix = "/pixiv-oauth";
        } else {
            // 图片 / accounts / 其他域名不代理
            return chain.proceed(request);
        }

        final String base = normalizeBase(proxy);
        final String query = url.encodedQuery();
        final String newUrlStr = base + prefix + url.encodedPath()
                + (query != null && !query.isEmpty() ? "?" + query : "");
        final HttpUrl newUrl = HttpUrl.parse(newUrlStr);
        if (newUrl == null) {
            Timber.w("AppApiProxyInterceptor: 无法解析代理 URL: %s", newUrlStr);
            return chain.proceed(request);
        }

        Timber.d("AppApiProxy → %s %s", request.method(), newUrl);
        return chain.proceed(request.newBuilder().url(newUrl).build());
    }

    /** 规范化代理地址：补 https:// 前缀、去末尾斜杠。 */
    static String normalizeBase(String proxy) {
        String p = proxy.trim();
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.contains("://")) p = "https://" + p;
        return p;
    }
}
