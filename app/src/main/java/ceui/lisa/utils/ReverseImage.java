package ceui.lisa.utils;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import ceui.lisa.activities.TemplateActivity;

/**
 * 以图搜图。
 *
 * <p>早先的实现是自己用 OkHttp 发 multipart POST，再把返回的 HTML 塞进 WebView 的
 * {@code loadDataWithBaseURL} 回放。这条路已经彻底走不通了（#733）：SauceNAO 和 ascii2d
 * 都把上传接口放到了 Cloudflare 的 JS 质询后面，headless 请求一律拿到
 * {@code 403 cf-mitigated: challenge} 的「Just a moment...」页
 * （换 UA、补 Rails 的 authenticity_token、补全套浏览器指纹头都试过，全是 403），
 * 而质询页在 {@code loadDataWithBaseURL} 里没有真 origin、没有 cookie jar，永远解不开。</p>
 *
 * <p>所以上传这件事只能交给 WebView 自己做——它是真浏览器，能跑质询 JS、能存
 * cf_clearance。这个类现在只负责把「本地图片 + 引擎首页」递给
 * {@link ceui.lisa.fragments.FragmentWebView}，由它把图片喂进页面自己的 file input。</p>
 */
public class ReverseImage {
    public static final long IMAGE_MAX_SIZE = 15 * 1024 * 1024; // SauceNao limit: 15MB
    public static final ReverseProvider DEFAULT_ENGINE = ReverseProvider.SauceNao;

    public static boolean isFileSizeOkToSearch(Uri fileUri) {
        return Common.isFileSizeOkToReverseSearch(fileUri, IMAGE_MAX_SIZE);
    }

    /**
     * 打开引擎的上传页，并把 imageUri 预挂给该页面的文件选择器。
     *
     * @param imageUri 必须是 FileProvider 的 content:// Uri，见
     *                 {@link Common#copyUriToReverseSearchCache(Uri)}
     */
    public static void search(Context context, Uri imageUri, ReverseProvider provider) {
        Intent intent = new Intent(context, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "以图搜图");
        intent.putExtra(Params.TITLE, provider.displayName);
        intent.putExtra(Params.URL, provider.uploadPageUrl);
        intent.putExtra(Params.REVERSE_SEARCH_IMAGE_URI, imageUri);
        context.startActivity(intent);
    }

    public enum ReverseProvider {
        SauceNao("SauceNao", "https://saucenao.com/"),
        Ascii2D("Ascii2D", "https://ascii2d.net/");

        public final String displayName;
        /** 引擎的上传页。WebView 先真实 GET 这一页拿到 cookie，再由页面自己 POST。 */
        public final String uploadPageUrl;

        ReverseProvider(String displayName, String uploadPageUrl) {
            this.displayName = displayName;
            this.uploadPageUrl = uploadPageUrl;
        }
    }
}
