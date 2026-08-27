package ceui.lisa.http;

import android.content.Context;
import android.util.Log;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.ExperimentalCronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * OkHttp Interceptor 将请求通过 Cronet (QUIC/HTTP3) 发送。
 * GFW 对 pixiv SNI 做了 TCP RST，QUIC 走 UDP 可绕过。
 */
public class CronetInterceptor implements Interceptor {

    private static final String TAG = "CronetInterceptor";
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10MB
    private static final long CANCEL_POLL_MILLIS = 100L;
    private static final long REQUEST_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    /**
     * Cronet direct-connect exists only to reach Pixiv-owned endpoints that are blocked by SNI.
     * Keeping this as an exact allowlist is also defense in depth for callers: a client that
     * accidentally installs the interceptor must never reroute pixshaft, a user proxy, or another
     * third-party origin through Cronet and silently lose that client's OkHttp policy.
     */
    private static final Set<String> DIRECT_CONNECT_HOSTS = new HashSet<>(Arrays.asList(
            "app-api.pixiv.net",
            "oauth.secure.pixiv.net",
            "www.pixiv.net",
            "accounts.pixiv.net",
            "comic.pixiv.net",
            "api.fanbox.cc"
    ));

    // Cloudflare Anycast IPs for Pixiv API — shared with HttpDns
    public static final String CF_IP_PRIMARY = "104.18.42.239";
    public static final String CF_IP_SECONDARY = "172.64.145.17";

    private static volatile CronetEngine sEngine;
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);

    public static CronetEngine getEngine(Context context) {
        if (sEngine == null) {
            synchronized (CronetInterceptor.class) {
                if (sEngine == null) {
                    sEngine = buildEngine(context);
                }
            }
        }
        return sEngine;
    }

    private final CronetEngine engine;

    public CronetInterceptor(CronetEngine engine) {
        this.engine = engine;
    }

    public static boolean shouldInterceptHost(String host) {
        return host != null && DIRECT_CONNECT_HOSTS.contains(host.toLowerCase(Locale.US));
    }

    static long requestTimeoutMillis() {
        return REQUEST_TIMEOUT_MILLIS;
    }

    private static CronetEngine buildEngine(Context context) {
        // www.pixiv.net 与 API 域名同在 Cloudflare 上,同一组 anycast IP 按 SNI 分流即可命中
        // (issue #959:拉黑等网页端专属接口要在直连下可用)。
        String rules = "MAP app-api.pixiv.net " + CF_IP_PRIMARY + ","
                + " MAP oauth.secure.pixiv.net " + CF_IP_PRIMARY + ","
                + " MAP www.pixiv.net " + CF_IP_PRIMARY;
        String experimental = "{\"HostResolverRules\":{\"host_resolver_rules\":\"" + rules + "\"}}";

        File cacheDir = new File(context.getCacheDir(), "cronet");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        return new ExperimentalCronetEngine.Builder(context)
                .enableQuic(true)
                .enableHttp2(true)
                .setStoragePath(cacheDir.getAbsolutePath())
                .addQuicHint("app-api.pixiv.net", 443, 443)
                .addQuicHint("oauth.secure.pixiv.net", 443, 443)
                .addQuicHint("www.pixiv.net", 443, 443)
                .setExperimentalOptions(experimental)
                .build();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!shouldInterceptHost(request.url().host())) {
            return chain.proceed(request);
        }
        String url = request.url().toString();
        String method = request.method();
        String path = request.url().encodedPath();
        String query = request.url().encodedQuery();
        String shortUrl = path + (query != null ? "?" + query : "");

        long startTime = System.nanoTime();
        Log.d(TAG, "──→ " + method + " " + shortUrl);

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final Throwable[] error = {null};
        final UrlResponseInfo[] responseInfo = {null};
        final long[] ttfb = {0};

        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, new UrlRequest.Callback() {
            @Override
            public void onRedirectReceived(UrlRequest req, UrlResponseInfo info, String newUrl) {
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.d(TAG, "  ↳ redirect [" + elapsed + "ms] → " + newUrl);
                req.followRedirect();
            }

            @Override
            public void onResponseStarted(UrlRequest req, UrlResponseInfo info) {
                responseInfo[0] = info;
                ttfb[0] = (System.nanoTime() - startTime) / 1_000_000;
                Log.d(TAG, "  ↳ response started [TTFB " + ttfb[0] + "ms] "
                        + info.getHttpStatusCode() + " " + info.getNegotiatedProtocol());
                req.read(ByteBuffer.allocateDirect(32768));
            }

            @Override
            public void onReadCompleted(UrlRequest req, UrlResponseInfo info, ByteBuffer buf) {
                buf.flip();
                int remaining = buf.remaining();
                if (bos.size() + remaining > MAX_RESPONSE_BYTES) {
                    error[0] = new IOException("Response exceeds " + MAX_RESPONSE_BYTES + " bytes");
                    req.cancel();
                    return;
                }
                byte[] tmp = new byte[remaining];
                buf.get(tmp);
                bos.write(tmp, 0, remaining);
                buf.clear();
                req.read(buf);
            }

            @Override
            public void onSucceeded(UrlRequest req, UrlResponseInfo info) {
                latch.countDown();
            }

            @Override
            public void onFailed(UrlRequest req, UrlResponseInfo info, CronetException e) {
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.e(TAG, "  ✗ failed [" + elapsed + "ms] " + e.getMessage());
                error[0] = e;
                latch.countDown();
            }

            @Override
            public void onCanceled(UrlRequest req, UrlResponseInfo info) {
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.w(TAG, "  ✗ cancelled [" + elapsed + "ms]");
                if (error[0] == null) {
                    error[0] = new IOException("Request cancelled");
                }
                latch.countDown();
            }
        }, sExecutor);

        // 转发 OkHttp headers
        Headers headers = request.headers();
        for (int i = 0; i < headers.size(); i++) {
            builder.addHeader(headers.name(i), headers.value(i));
        }

        // 设置 HTTP method 和 body
        if (request.body() != null) {
            Buffer buf = new Buffer();
            request.body().writeTo(buf);
            byte[] bodyBytes = buf.readByteArray();
            MediaType contentType = request.body().contentType();
            builder.setUploadDataProvider(
                    org.chromium.net.UploadDataProviders.create(bodyBytes),
                    sExecutor
            );
            if (contentType != null) {
                builder.addHeader("Content-Type", contentType.toString());
            }
            builder.setHttpMethod(method);
        } else if (!"GET".equals(method)) {
            builder.setHttpMethod(method);
        }

        UrlRequest urlRequest = builder.build();
        urlRequest.start();

        // Keep the existing 30-second Cronet deadline: the owning clients' connect/read timeouts
        // were never applied to this fully-buffered path, and shortening the total here would
        // change successful slow Pixiv/FANBOX/comic requests into failures. Polling the OkHttp call
        // still propagates Retrofit/coroutine cancellation instead of leaving Cronet running after
        // the screen has gone away.
        long timeoutMillis = requestTimeoutMillis();
        long deadlineNanos = startTime + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (latch.getCount() != 0L) {
            if (chain.call().isCanceled()) {
                urlRequest.cancel();
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.w(TAG, "←── " + method + " " + shortUrl + " CANCELLED [" + elapsed + "ms]");
                throw new IOException("Canceled");
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                urlRequest.cancel();
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.e(TAG, "←── " + method + " " + shortUrl + " TIMEOUT [" + elapsed + "ms]");
                throw new SocketTimeoutException("Cronet request timed out after " + timeoutMillis + "ms");
            }
            long waitMillis = Math.min(
                    CANCEL_POLL_MILLIS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            );
            try {
                latch.await(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                urlRequest.cancel();
                Thread.currentThread().interrupt();
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                Log.e(TAG, "←── " + method + " " + shortUrl + " INTERRUPTED [" + elapsed + "ms]");
                InterruptedIOException interrupted =
                        new InterruptedIOException("Cronet request interrupted");
                interrupted.initCause(e);
                throw interrupted;
            }
        }

        if (chain.call().isCanceled()) {
            urlRequest.cancel();
            throw new IOException("Canceled");
        }
        if (error[0] != null) {
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            Log.e(TAG, "←── " + method + " " + shortUrl + " FAILED [" + elapsed + "ms] " + error[0].getMessage());
            throw new IOException("Cronet failed: " + error[0].getMessage(), error[0]);
        }

        if (responseInfo[0] == null) {
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            Log.e(TAG, "←── " + method + " " + shortUrl + " NO_RESPONSE [" + elapsed + "ms]");
            throw new IOException("No response received");
        }

        long totalTime = (System.nanoTime() - startTime) / 1_000_000;

        // 构建 OkHttp Response
        UrlResponseInfo info = responseInfo[0];
        Headers.Builder responseHeaders = new Headers.Builder();
        for (Map.Entry<String, List<String>> entry : info.getAllHeaders().entrySet()) {
            String key = entry.getKey();
            if ("content-encoding".equalsIgnoreCase(key) || "content-length".equalsIgnoreCase(key)) {
                continue;
            }
            for (String value : entry.getValue()) {
                responseHeaders.add(key, value);
            }
        }

        String protocol = info.getNegotiatedProtocol();
        Protocol okProtocol;
        if ("h3".equals(protocol) || "quic".equals(protocol)) {
            okProtocol = Protocol.QUIC;
        } else if ("h2".equals(protocol)) {
            okProtocol = Protocol.HTTP_2;
        } else {
            okProtocol = Protocol.HTTP_1_1;
        }

        byte[] body = bos.toByteArray();
        long bodyReadTime = totalTime - ttfb[0];
        Log.d(TAG, "←── " + method + " " + shortUrl + " " + info.getHttpStatusCode()
                + " " + protocol + " [total " + totalTime + "ms | TTFB " + ttfb[0]
                + "ms | body " + bodyReadTime + "ms | " + body.length + " bytes]");

        return new Response.Builder()
                .request(request)
                .protocol(okProtocol)
                .code(info.getHttpStatusCode())
                .message(info.getHttpStatusText() != null ? info.getHttpStatusText() : "")
                .headers(responseHeaders.build())
                .body(ResponseBody.create(body, MediaType.parse(
                        info.getAllHeaders().containsKey("content-type")
                                ? info.getAllHeaders().get("content-type").get(0)
                                : "application/json")))
                .build();
    }
}
