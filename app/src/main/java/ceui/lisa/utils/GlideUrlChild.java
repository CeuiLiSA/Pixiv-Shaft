package ceui.lisa.utils;

import android.net.Uri;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.Headers;

import java.net.URL;
import java.util.HashMap;

import ceui.lisa.http.PixivHeaders;

public class GlideUrlChild extends GlideUrl {

    public GlideUrlChild(String url) {
        this(url, getHeaders(url));
    }

    public GlideUrlChild(String url, Headers headers) {
        super(url, headers);
    }

    private static Headers getHeaders(String url) {
        PixivHeaders pixivHeaders = new PixivHeaders();
        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put(Params.MAP_KEY_SMALL, Params.IMAGE_REFERER);
        hashMap.put("x-client-time", pixivHeaders.getXClientTime());
        hashMap.put("x-client-hash", pixivHeaders.getXClientHash());
        hashMap.put(Params.USER_AGENT, Params.PHONE_MODEL);
        try {
            hashMap.put(Params.HOST, Uri.parse(url).getHost());
        } catch (Exception e) {
            e.printStackTrace();
            hashMap.put(Params.HOST, Params.HOST_NAME);
        }
        return () -> hashMap;
    }
}
