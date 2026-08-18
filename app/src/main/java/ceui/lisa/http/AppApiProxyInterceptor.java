package ceui.lisa.http;

import java.io.IOException;

import ceui.lisa.BuildConfig;
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
 * <p>与直连（Cronet）**不互斥**：本拦截器挂在 {@code CronetInterceptor} 之前，
 * 改写后的域名（用户自建代理）不在 Cronet MAP 规则内，自然走系统 DNS/TLS 解析；
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

        final boolean enabled = Shaft.sSettings != null && Shaft.sSettings.isUseAppApiProxy();
        if (!enabled) return chain.proceed(request);

        final String proxy = Shaft.sSettings != null ? Shaft.sSettings.getAppApiProxy() : "";
        // 图片 / accounts / 其他域名不代理
        if (!APP_API_HOST.equals(url.host()) && !OAUTH_HOST.equals(url.host())) {
            return chain.proceed(request);
        }

        final HttpUrl newUrl = rewrite(url, proxy);
        if (newUrl == null) {
            // 地址非法（scheme 不允许 / 带 query / 解析失败）：不静默裸连，明确告警
            Timber.w("AppApiProxyInterceptor: 代理地址非法已忽略（需 https://，debug 另可显式 http://，且不含 query）: %s", proxy);
            return chain.proceed(request);
        }

        Timber.d("AppApiProxy → %s %s", request.method(), newUrl);
        return chain.proceed(request.newBuilder().url(newUrl).build());
    }

    /**
     * 将 app-api / oauth 请求改写为代理路径。纯函数，不依赖 Android 环境，便于单元测试。
     *
     * <p>公开给网络测试页（NetworkTestViewModel）复用：探测转发路径时必须发**生产会发的那个
     * URL**，自己手拼 {@code root + "/pixiv-app-api"} 会绕开下面的双前缀去重，把用户按
     * 「误填完整 PxveAPI 地址」形态配好的、实际能用的代理误报成 404。</p>
     *
     * @param original 原始请求 URL（app-api.pixiv.net 或 oauth.secure.pixiv.net）
     * @param proxy    用户配置的代理根地址（原始字符串）
     * @return 改写后的 URL；代理地址非法或为空时返回 null（调用方决定放行原请求）
     */
    public static HttpUrl rewrite(HttpUrl original, String proxy) {
        if (proxy == null || proxy.trim().isEmpty()) return null;

        final String prefix;
        if (APP_API_HOST.equals(original.host())) {
            prefix = "/pixiv-app-api";
        } else if (OAUTH_HOST.equals(original.host())) {
            prefix = "/pixiv-oauth";
        } else {
            return null;
        }

        final String baseStr = normalizeBase(proxy);
        if (baseStr == null) return null;
        final HttpUrl base;
        try {
            base = HttpUrl.get(baseStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // 只用 base 的 scheme://host[:port] 作为根，路径部分单独规范化：
        // 用户可能误填完整 PxveAPI 地址（如 https://proxy/pixiv-app-api），
        // 去掉路径里已存在的同名前缀，避免拼出 /pixiv-app-api/pixiv-app-api 双前缀 404。
        // 默认端口按 base 自身的 scheme 取：debug 下允许 http 代理后，恒用 https 的 443
        // 会给 80 端口的 http 代理拼出多余的 ":80"，和网络测试页展示/探测的 URL 对不上。
        final String root = base.scheme() + "://" + base.host()
                + (base.port() != HttpUrl.defaultPort(base.scheme()) ? ":" + base.port() : "");
        String basePath = base.encodedPath();
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        if (basePath.endsWith(prefix)) {
            basePath = basePath.substring(0, basePath.length() - prefix.length());
        }

        final String newUrlStr = root + basePath + prefix + original.encodedPath()
                + (original.encodedQuery() != null && !original.encodedQuery().isEmpty()
                        ? "?" + original.encodedQuery() : "");
        try {
            return HttpUrl.get(newUrlStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 规范化代理根地址，返回**无尾斜杠**的根地址字符串。
     *
     * <p>这是代理地址是否合法的**唯一事实源**：设置页保存前也调它做校验
     * （返回 null 即提示用户），避免 UI 侧另写一套判断跟这里的规则漂移。</p>
     *
     * <ul>
     *   <li>scheme：只接受 {@code https://}；其它 scheme 视为非法（返回 null），避免 Authorization
     *       令牌走明文。Debug 构建额外放行**显式**写出的 {@code http://}（本地起代理调试用），
     *       Release 一律拒绝。</li>
     *   <li>裸域名（不带 scheme）**任何 buildType 下都补 {@code https://}**：明文只能是用户
     *       显式写出来的选择，不由 buildType 替他决定。</li>
     *   <li>去掉末尾全部斜杠（含根路径 "/"，不止 1 个）。</li>
     *   <li>带 query / fragment 视为非法（代理根地址不应携带），避免拼出非法 URL。</li>
     *   <li>解析失败（非法字符等）返回 null。</li>
     * </ul>
     *
     * @return 规范化后的根地址（无尾斜杠），非法返回 null
     */
    public static String normalizeBase(String proxy) {
        String p = proxy == null ? "" : proxy.trim();
        if (p.isEmpty()) return null;

        if (p.contains("://")) {
            final boolean https = p.regionMatches(true, 0, "https://", 0, 8);
            // Debug 构建额外放行**显式**写出的 http://（本地起代理调试用）；Release 只收 https。
            final boolean debugHttp = BuildConfig.IS_DEBUG_MODE && p.regionMatches(true, 0, "http://", 0, 7);
            if (!https && !debugHttp) {
                return null;
            }
        } else {
            // 裸域名一律补 https：这是绝大多数人填写代理的方式，明文只能是用户显式写出来的选择，
            // 不能由 buildType 悄悄替他决定（否则 debug 包里 Authorization / refresh_token 走明文）。
            p = "https://" + p;
        }

        final HttpUrl url;
        try {
            url = HttpUrl.get(p);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (url.query() != null || url.fragment() != null) {
            return null;
        }

        // 去末尾全部斜杠（HttpUrl.toString 对根路径恒带 "/"，需一并去掉）
        String s = url.toString();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
