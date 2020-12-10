package ceui.lisa.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.Headers;

import java.util.HashMap;
import java.util.Map;

import ceui.lisa.download.IllustDownload;
import ceui.lisa.models.UserBean;
import ceui.lisa.models.IllustsBean;

public class GlideUtil {

    public static GlideUrl getMediumImg(IllustsBean illustsBean) {
        return new GlideUrlChild(illustsBean.getImage_urls().getMedium());
    }

    public static GlideUrl getMediumImg(String imageUrl) {
        return new GlideUrlChild(imageUrl);
    }

    public static GlideUrl getArticle(String url) {
        return new GlideUrlChild(url);
    }

    public static GlideUrl getLargeImage(IllustsBean illustsBean) {
        return new GlideUrlChild(illustsBean.getImage_urls().getLarge());
    }

    public static GlideUrl getHead(UserBean userBean) {
        if (userBean == null) {
            return null;
        }

        if (userBean.getProfile_image_urls() == null) {
            return null;
        }

        if (!TextUtils.isEmpty(userBean.getProfile_image_urls().getMaxImage())) {
            return new GlideUrlChild(userBean.getProfile_image_urls().getMaxImage());
        }

        return null;
    }


    public static GlideUrl getSquare(IllustsBean illustsBean) {
        return new GlideUrlChild(illustsBean.getImage_urls().getSquare_medium());
    }

    public static GlideUrl getLargeImage(IllustsBean illustsBean, int i) {
        if (illustsBean.getPage_count() == 1) {
            return getLargeImage(illustsBean);
        } else {
            return new GlideUrlChild(illustsBean.getMeta_pages().get(i).getImage_urls().getLarge());
        }
    }


    public static GlideUrl getOriginal(IllustsBean illustsBean, int i) {
        return new GlideUrlChild(IllustDownload.getUrl(illustsBean, i));
    }
}
