package ceui.lisa.utils;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.Headers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ceui.lisa.http.ImageHostManager;
import ceui.lisa.http.PixivHeaders;

public class GlideUrlChild extends GlideUrl {

    /**
     * 最近一次算出的图片请求头。x-client-time 只精确到秒，同一秒内重算得到的值完全相同，
     * 所以按秒复用，省掉每张图一次 SimpleDateFormat + MD5（详情页一屏几十张图都在主线程上算）。
     */
    private static volatile CachedHeaders sCachedHeaders;

    public GlideUrlChild(String url) {
        this(url, formatHeader());
    }

    public GlideUrlChild(String url, Headers headers) {
        // issue #865: single choke point for image loading — rewrite the pixiv
        // image host to the user's chosen host (Pixiv / pixiv.cat / custom).
        // rewrite() is a no-op in the default PIXIV mode and for non-pximg urls,
        // and is idempotent, so wrapping here (the sink both ctors reach) is safe.
        // The rewritten url also becomes the GlideUrl cache key, so switching
        // hosts naturally uses a distinct cache entry.
        super(ImageHostManager.INSTANCE.rewrite(url), headers);
    }

    private static Headers formatHeader() {
        long epochSecond = System.currentTimeMillis() / 1000L;
        CachedHeaders cached = sCachedHeaders;
        if (cached == null || cached.epochSecond != epochSecond) {
            PixivHeaders pixivHeaders = new PixivHeaders();
            HashMap<String, String> hashMap = new HashMap<>();
            hashMap.put(Params.MAP_KEY_SMALL, Params.IMAGE_REFERER);
            hashMap.put("x-client-time", pixivHeaders.getXClientTime());
            hashMap.put("x-client-hash", pixivHeaders.getXClientHash());
            hashMap.put(Params.USER_AGENT, Params.PHONE_MODEL);
            cached = new CachedHeaders(epochSecond, Collections.unmodifiableMap(hashMap));
            sCachedHeaders = cached;
        }
        Map<String, String> headers = cached.headers;
        // 每个 GlideUrl 仍拿一个新的 Headers 实例：GlideUrl.equals 会比较 headers，
        // 共享实例会让同一秒内同一 url 的两次请求被 Glide 判为等价而复用旧请求，丢掉新请求的回调。
        return () -> headers;
    }

    private static final class CachedHeaders {
        final long epochSecond;
        final Map<String, String> headers;

        CachedHeaders(long epochSecond, Map<String, String> headers) {
            this.epochSecond = epochSecond;
            this.headers = headers;
        }
    }
}
