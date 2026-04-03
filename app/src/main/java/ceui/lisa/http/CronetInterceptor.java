package ceui.lisa.http;

import android.content.Context;
import android.util.Log;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.ExperimentalCronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
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
    private final CronetEngine engine;
    private final Executor executor = Executors.newFixedThreadPool(4);

    public CronetInterceptor(CronetEngine engine) {
        this.engine = engine;
    }

    private static volatile CronetEngine sEngine;

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

    public static CronetEngine buildEngine(Context context) {
        // 绕过被污染的系统 DNS，将 Pixiv API 域名直接映射到 Cloudflare IP
        String rules = "MAP app-api.pixiv.net 104.18.42.239,"
                + " MAP oauth.secure.pixiv.net 104.18.42.239";
        String experimental = "{\"HostResolverRules\":{\"host_resolver_rules\":\"" + rules + "\"}}";
        return new ExperimentalCronetEngine.Builder(context)
                .enableQuic(true)
                .enableHttp2(true)
                .addQuicHint("app-api.pixiv.net", 443, 443)
                .addQuicHint("oauth.secure.pixiv.net", 443, 443)
                .setExperimentalOptions(experimental)
                .build();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String url = request.url().toString();

        // 构建 Cronet 请求
        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(bos);
        final Throwable[] error = {null};
        final UrlResponseInfo[] responseInfo = {null};

        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, new UrlRequest.Callback() {
            @Override
            public void onRedirectReceived(UrlRequest req, UrlResponseInfo info, String newUrl) {
                req.followRedirect();
            }

            @Override
            public void onResponseStarted(UrlRequest req, UrlResponseInfo info) {
                responseInfo[0] = info;
                Log.d(TAG, "Protocol: " + info.getNegotiatedProtocol() + " Status: " + info.getHttpStatusCode());
                req.read(ByteBuffer.allocateDirect(65536));
            }

            @Override
            public void onReadCompleted(UrlRequest req, UrlResponseInfo info, ByteBuffer buf) {
                buf.flip();
                try {
                    channel.write(buf);
                } catch (IOException e) {
                    error[0] = e;
                }
                buf.clear();
                req.read(buf);
            }

            @Override
            public void onSucceeded(UrlRequest req, UrlResponseInfo info) {
                latch.countDown();
            }

            @Override
            public void onFailed(UrlRequest req, UrlResponseInfo info, CronetException e) {
                error[0] = e;
                latch.countDown();
            }
        }, executor);

        // 转发 OkHttp headers
        Headers headers = request.headers();
        for (int i = 0; i < headers.size(); i++) {
            builder.addHeader(headers.name(i), headers.value(i));
        }

        // 设置 HTTP method 和 body
        String method = request.method();
        if (request.body() != null) {
            Buffer buf = new Buffer();
            request.body().writeTo(buf);
            byte[] bodyBytes = buf.readByteArray();
            MediaType contentType = request.body().contentType();
            builder.setUploadDataProvider(
                    org.chromium.net.UploadDataProviders.create(bodyBytes),
                    executor
            );
            if (contentType != null) {
                builder.addHeader("Content-Type", contentType.toString());
            }
            builder.setHttpMethod(method);
        } else if (!"GET".equals(method)) {
            builder.setHttpMethod(method);
        }

        builder.build().start();

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IOException("Cronet request timed out: " + url);
            }
        } catch (InterruptedException e) {
            throw new IOException("Cronet request interrupted", e);
        }

        if (error[0] != null) {
            throw new IOException("Cronet failed: " + error[0].getMessage(), error[0]);
        }

        if (responseInfo[0] == null) {
            throw new IOException("No response received");
        }

        // 构建 OkHttp Response
        UrlResponseInfo info = responseInfo[0];
        Headers.Builder responseHeaders = new Headers.Builder();
        for (Map.Entry<String, List<String>> entry : info.getAllHeaders().entrySet()) {
            // Cronet 已自动解压 gzip/brotli，去掉这些 header 防止 OkHttp 再次解压
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
            okProtocol = Protocol.H2_PRIOR_KNOWLEDGE;
        } else {
            okProtocol = Protocol.HTTP_1_1;
        }

        byte[] body = bos.toByteArray();
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
