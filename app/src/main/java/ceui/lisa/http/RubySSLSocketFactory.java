package ceui.lisa.http;


import android.net.SSLCertificateSocketFactory;
import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import kotlin.TypeCastException;
import kotlin.jvm.internal.Intrinsics;

public final class RubySSLSocketFactory extends SSLSocketFactory {

    private HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

    @Nullable
    public Socket createSocket(@Nullable String paramString, int paramInt) {
        return null;
    }

    @Nullable
    public Socket createSocket(@Nullable String paramString, int paramInt1, @Nullable InetAddress paramInetAddress, int paramInt2) {
        return null;
    }

    @Nullable
    public Socket createSocket(@Nullable InetAddress paramInetAddress, int paramInt) {
        return null;
    }

    @Nullable
    public Socket createSocket(@Nullable InetAddress paramInetAddress1, int paramInt1, @Nullable InetAddress paramInetAddress2, int paramInt2) {
        return null;
    }

    @NotNull
    public Socket createSocket(@Nullable Socket paramSocket, @Nullable String paramString, int paramInt, boolean paramBoolean) throws IOException {
        if (paramSocket == null) {
            Intrinsics.throwNpe();
        }
        InetAddress inetAddress = paramSocket.getInetAddress();
        Intrinsics.checkExpressionValueIsNotNull(inetAddress, "address");
        Log.d("createSocket address1", inetAddress.getHostAddress());
        // okhttp3 4.5.0 版本引入修改，okhttp3.internal.connection.RealConnection->isHealthy中，检查了rawSocket.isClosed状态，如果需要更新到高版本依然可用，注释下方2行
        if (paramBoolean)
            paramSocket.close();
        SocketFactory socketFactory = SSLCertificateSocketFactory.getDefault(0);
        if (socketFactory != null) {
            Socket socket = socketFactory.createSocket(inetAddress, paramInt);
            if (socket != null) {
                ((SSLSocket) socket).setEnabledProtocols(((SSLSocket) socket).getSupportedProtocols());
                Log.i("X", "Setting SNI hostname");
                SSLSession sSLSession = ((SSLSocket) socket).getSession();
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("Established ");
                Intrinsics.checkExpressionValueIsNotNull(sSLSession, "session");
                stringBuilder.append(sSLSession.getProtocol());
                stringBuilder.append(" connection with ");
                stringBuilder.append(sSLSession.getPeerHost());
                stringBuilder.append(" using ");
                stringBuilder.append(sSLSession.getCipherSuite());
                Log.d("X", stringBuilder.toString());
                return socket;
            }
            throw new TypeCastException("null cannot be cast to non-null type javax.net.ssl.SSLSocket");
        }
        throw new TypeCastException("null cannot be cast to non-null type android.net.SSLCertificateSocketFactory");
    }

    @NotNull
    public String[] getDefaultCipherSuites() {
        return new String[0];
    }

    @NotNull
    public String[] getSupportedCipherSuites() {
        return new String[0];
    }
}
