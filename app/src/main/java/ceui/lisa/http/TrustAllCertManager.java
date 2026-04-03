package ceui.lisa.http;

import android.annotation.SuppressLint;

import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * 信任所有证书的 TrustManager。
 * 用于直连模式下图片服务器的无 SNI 连接（GFW 按 SNI 封锁，配合 RubySSLSocketFactory 绕过）。
 */
@SuppressLint("CustomX509TrustManager")
public class TrustAllCertManager implements X509TrustManager {
    public void checkClientTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {
    }

    public void checkServerTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {
    }

    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}