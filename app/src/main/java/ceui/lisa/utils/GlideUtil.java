package ceui.lisa.utils;

import android.text.TextUtils;

import com.bumptech.glide.load.model.GlideUrl;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserBean;

import static ceui.lisa.core.UrlFactory.invoke;

public class GlideUtil {

    public static GlideUrl getMediumImg(IllustsBean illustsBean) {
        return new GlideUrlChild(invoke(illustsBean.getImage_urls().getMedium()));
    }

    public static GlideUrl getUrl(String url) {
        return new GlideUrlChild(invoke(url));
    }

    public static GlideUrl getLargeImage(IllustsBean illustsBean) {
        Common.showLog("getLargeImage 00 ");
        return new GlideUrlChild(invoke(illustsBean.getImage_urls().getLarge()));
    }

    public static final String DEFAULT_HEAD_IMAGE = "https://s.pximg.net/common/images/no_profile.png";

    public static GlideUrl getHead(UserBean userBean) {
        if (userBean == null) {
            return null;
        }

        if (userBean.getProfile_image_urls() == null) {
            return null;
        }

        String image = userBean.getProfile_image_urls().getMaxImage();

        if (TextUtils.equals(image, DEFAULT_HEAD_IMAGE)) {
            return new GlideUrlChild(image);
        } else {
            return new GlideUrlChild(invoke(userBean.getProfile_image_urls().getMaxImage()));
        }
    }


    public static GlideUrl getSquare(IllustsBean illustsBean) {
        return new GlideUrlChild(invoke(illustsBean.getImage_urls().getSquare_medium()));
    }

    public static GlideUrl getLargeImage(IllustsBean illustsBean, int i) {
        Common.showLog("getLargeImage 11 ");
        if (illustsBean.getPage_count() == 1) {
            return getLargeImage(illustsBean);
        } else {
            return new GlideUrlChild(invoke(illustsBean.getMeta_pages().get(i).getImage_urls().getLarge()));
        }
    }

    public static GlideUrl getOriginalImage(IllustsBean illustsBean, int i) {
        if (illustsBean.getPage_count() == 1) {
            return new GlideUrlChild(illustsBean.getMeta_single_page().getOriginal_image_url());
        } else {
            return new GlideUrlChild(illustsBean.getMeta_pages().get(i).getImage_urls().getOriginal());
        }
    }
}
