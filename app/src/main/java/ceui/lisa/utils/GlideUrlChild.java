package ceui.lisa.utils;

import android.net.Uri;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.Headers;

import java.net.URL;
import java.util.HashMap;

public class GlideUrlChild extends GlideUrl {

    public GlideUrlChild(URL url) {
        super(url);
    }

    public GlideUrlChild(String url) {
        this(url, getHeaders(url));
    }

    public GlideUrlChild(URL url, Headers headers) {
        super(url, headers);
    }

    public GlideUrlChild(String url, Headers headers) {
        super(url, headers);
    }

    private static Headers getHeaders(String url) {
        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put(Params.MAP_KEY_SMALL, Params.IMAGE_REFERER);
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
