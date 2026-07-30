package ceui.lisa.utils;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import ceui.lisa.R;
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
     * 从外部 Uri 起一次图搜：查大小 → 复制进缓存 → 打开引擎页。
     *
     * <p>前两步都在子线程。source 可能是云相册的 content://（Google Photos 这类只存云端的
     * 图片），ContentResolver 读它会先联网下载——最多 15MB，放主线程足够撞 ANR。</p>
     *
     * @param after 准备结束后回主线程执行，成功失败都会走到。分享进来的那张中转 activity
     *              用它来 finish（不能提前 finish：起 TemplateActivity 还要用它的 context）。
     */
    public static void searchFrom(Activity activity, Uri source, ReverseProvider provider,
                                  @Nullable Runnable after) {
        Common.showToast(activity.getString(R.string.loading_text));
        new Thread(() -> {
            final boolean sizeOk = isFileSizeOkToSearch(source);
            final Uri cached = sizeOk ? Common.copyUriToReverseSearchCache(source) : null;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    if (!sizeOk) {
                        Common.showToast(activity.getString(R.string.string_410));
                    } else if (cached == null) {
                        Common.showToast(activity.getString(R.string.reverse_image_copy_failed));
                    } else {
                        search(activity, cached, provider);
                    }
                }
                if (after != null) {
                    after.run();
                }
            });
        }, "reverse-image-prepare").start();
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
