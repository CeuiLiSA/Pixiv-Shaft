package ceui.lisa.http;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SSLSocketClient {

    public static HostnameVerifier getHostnameVerifier() { return new HostnameVerifier() {
        public boolean verify(String param1String, SSLSession param1SSLSession) { return true; }
    }; }

    public static SSLSocketFactory getSSLSocketFactory() {
        try {
            SSLContext sSLContext = SSLContext.getInstance("SSL");
            sSLContext.init(null, getTrustManager(), new SecureRandom());
            return sSLContext.getSocketFactory();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static TrustManager[] getTrustManager() { return new TrustManager[] { new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {}

        public void checkServerTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {}

        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    } }; }

}
