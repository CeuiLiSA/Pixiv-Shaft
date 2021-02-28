package ceui.lisa.feature;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

import ceui.lisa.utils.Dev;
//import weiss.Weiss;

/**
 *
 * 出自Notsfsssf/pixez-flutter，感谢原作者辛苦贡献
 *
 * https://github.com/Notsfsssf/pixez-flutter/blob/c784b265adf7598f0856a74fe055cef784034c15/android/app/src/main/kotlin/com/perol/pixez/Weiss.kt
 */
public class WeissUtil {

    public static final int PORT = 9801;

    public static void start() {
        if (!Dev.use_weiss) {
            return;
        }
        try {
//            Weiss.start(String.valueOf(PORT));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void end() {
        if (!Dev.use_weiss) {
            return;
        }
        try {
//            Weiss.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void proxy() {
        if (!Dev.use_weiss) {
            return;
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                String proxyUrl = "127.0.0.1:" + PORT;
                ProxyConfig proxyConfig = new ProxyConfig.Builder()
                        .addProxyRule(proxyUrl)
                        .addDirect()
                        .build();
                ProxyController.getInstance().setProxyOverride(proxyConfig, new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        command.run();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
