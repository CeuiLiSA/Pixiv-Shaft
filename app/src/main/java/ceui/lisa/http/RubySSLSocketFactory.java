package ceui.lisa.http;

import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * 不发送 SNI 的 SSLSocketFactory。
 * 用于图片服务器 (i.pximg.net)：GFW 按 SNI 封锁，不发 SNI 则 GFW 看不到域名。
 * 图片服务器不要求 SNI（基于 IP 路由）。
 */
public final class RubySSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public RubySSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new pixivOkHttpClient()}, null);
            delegate = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public Socket createSocket(@Nullable String host, int port) { return null; }

    @Nullable
    public Socket createSocket(@Nullable String host, int port, @Nullable InetAddress localAddr, int localPort) { return null; }

    @Nullable
    public Socket createSocket(@Nullable InetAddress addr, int port) { return null; }

    @Nullable
    public Socket createSocket(@Nullable InetAddress addr, int port, @Nullable InetAddress localAddr, int localPort) { return null; }

    @NotNull
    public Socket createSocket(@Nullable Socket socket, @Nullable String host, int port, boolean autoClose) throws IOException {
        if (socket == null) throw new NullPointerException("socket is null");
        Log.d("RubySSL", "No-SNI connect to " + socket.getInetAddress().getHostAddress());
        // 传 null hostname → Java TLS 不在 ClientHello 中包含 SNI 扩展
        SSLSocket sslSocket = (SSLSocket) delegate.createSocket(socket, null, port, autoClose);
        sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());
        return sslSocket;
    }

    @NotNull
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @NotNull
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
}
