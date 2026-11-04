package ceui.lisa.utils;

import android.text.TextUtils;

import com.bumptech.glide.load.model.GlideUrl;


import ceui.pixiv.api.model.Illust;
import ceui.loxia.ImageUrls;
import ceui.pixiv.api.model.MetaPage;
import ceui.loxia.User;

import java.util.List;


public class GlideUtil {

    public static GlideUrl getMediumImg(Illust illustsBean) {
        ImageUrls urls = illustsBean.getImage_urls();
        return urls == null ? null : getUrl(urls.getMedium());
    }

    public static GlideUrl getUrl(String url) {
        // 空 URL 时返回 null,交给 Glide 的 .error()/.fallback() 兜底,而不是让 GlideUrl 构造抛
        // "Must not be null or empty"。精简/网页来源的 bean 缺头像 URL 时尤其会触发(issue #569)。
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        return new GlideUrlChild((url));
    }

    public static GlideUrl getLargeImage(Illust illustsBean) {
        ImageUrls urls = illustsBean.getImage_urls();
        return urls == null ? null : getUrl(urls.getLarge());
    }

    public static final String DEFAULT_HEAD_IMAGE = "https://s.pximg.net/common/images/no_profile.png";

    public static GlideUrl getHead(User user) {
        if (user == null) {
            return null;
        }

        ImageUrls urls = user.getProfile_image_urls();
        if (urls == null) {
            return null;
        }

        String image = urls.findMaxSizeUrl();
        return getUrl(image);
    }

    public static GlideUrl getSquare(Illust illustsBean) {
        ImageUrls urls = illustsBean.getImage_urls();
        return urls == null ? null : getUrl(urls.getSquare_medium());
    }

    public static GlideUrl getLargeImage(Illust illustsBean, int i) {
        Common.showLog("getLargeImage 11 ");
        if (illustsBean.getPage_count() == 1) {
            return getLargeImage(illustsBean);
        }
        // 精简/网页来源的多图 bean 没有 meta_pages,这里降级到封面 large,避免 .get(i) NPE(issue #569)。
        List<MetaPage> mp = illustsBean.getMeta_pages();
        if (mp == null || i < 0 || i >= mp.size()) {
            return getLargeImage(illustsBean);
        }
        MetaPage page = mp.get(i);
        ImageUrls urls = page == null ? null : page.getImage_urls();
        GlideUrl pageUrl = urls == null ? null : getUrl(urls.getLarge());
        return pageUrl == null ? getLargeImage(illustsBean) : pageUrl;
    }

    public static GlideUrl getOriginalImage(Illust illustsBean, int i) {
        if (illustsBean.getPage_count() == 1) {
            // meta_single_page 缺失时降级到封面 large,避免 NPE(issue #569)。
            if (illustsBean.getMeta_single_page() == null
                    || TextUtils.isEmpty(illustsBean.getMeta_single_page().getOriginal_image_url())) {
                return getLargeImage(illustsBean);
            }
            return getUrl(illustsBean.getMeta_single_page().getOriginal_image_url());
        }
        List<MetaPage> mp = illustsBean.getMeta_pages();
        if (mp == null || i < 0 || i >= mp.size()) {
            return getLargeImage(illustsBean);
        }
        MetaPage page = mp.get(i);
        ImageUrls urls = page == null ? null : page.getImage_urls();
        GlideUrl pageUrl = urls == null ? null : getUrl(urls.getOriginal());
        return pageUrl == null ? getLargeImage(illustsBean, i) : pageUrl;
    }
}
