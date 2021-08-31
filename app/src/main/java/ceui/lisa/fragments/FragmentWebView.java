package ceui.lisa.fragments;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.SSLCertificateSocketFactory;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebViewClient;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import ceui.lisa.R;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.databinding.FragmentWebviewBinding;
import ceui.lisa.feature.WeissUtil;
import ceui.lisa.http.HttpDns;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;
import ceui.lisa.view.ContextMenuTitleView;

public class FragmentWebView extends BaseFragment<FragmentWebviewBinding> {

    //private static final String ILLUST_HEAD = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=";
    private static final String USER_HEAD = "https://www.pixiv.net/member.php?id=";
    private static final String WORKS_HEAD = "https://www.pixiv.net/artworks/";
    private static final String PIXIV_HEAD = "https://www.pixiv.net/";
    private static final String ACCOUNT_URL = "intent://account/";
    private static final String PIXIVISION_HEAD = "https://www.pixivision.net/";
    private static final String TAG = "FragmentWebView";
    private String title;
    private String url;
    private String response = null;
    private String mime = null;
    private String encoding = null;
    private String historyUrl = null;
    private boolean preferPreserve = false;
    private AgentWeb mAgentWeb;
    private WebView mWebView;
    private String mIntentUrl;
    private final WebViewClickHandler handler = new WebViewClickHandler();
    private final HttpDns httpDns = HttpDns.getInstance();
    private String mLongClickLinkText;
    private Uri reverseSearchImageUri;

    @Override
    public void initBundle(Bundle bundle) {
        title = bundle.getString(Params.TITLE);
        url = bundle.getString(Params.URL);
        response = bundle.getString(Params.RESPONSE);
        mime = bundle.getString(Params.MIME);
        encoding = bundle.getString(Params.ENCODING);
        historyUrl = bundle.getString(Params.HISTORY_URL);
        preferPreserve = bundle.getBoolean(Params.PREFER_PRESERVE);
        reverseSearchImageUri = bundle.getParcelable(Params.REVERSE_SEARCH_IMAGE_URI);
    }

    public static FragmentWebView newInstance(String title, String url) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        args.putString(Params.URL, url);
        FragmentWebView fragment = new FragmentWebView();
        fragment.setArguments(args);
        return fragment;
    }

    public static FragmentWebView newInstance(String title, String url, boolean preferPreserve) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        args.putString(Params.URL, url);
        args.putBoolean(Params.PREFER_PRESERVE, preferPreserve);
        FragmentWebView fragment = new FragmentWebView();
        fragment.setArguments(args);
        return fragment;
    }

    // 反向搜索
    public static FragmentWebView newInstance(String title, String url, String response,
                                              String mime, String encoding, String history_url, Uri imageUri) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        args.putString(Params.URL, url);
        args.putString(Params.RESPONSE, response);
        args.putString(Params.MIME, mime);
        args.putString(Params.ENCODING, encoding);
        args.putString(Params.HISTORY_URL, history_url);
        args.putParcelable(Params.REVERSE_SEARCH_IMAGE_URI, imageUri);
        FragmentWebView fragment = new FragmentWebView();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_webview;
    }

    @Override
    public void initView() {
        baseBind.toolbarTitle.setText(title);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        if (reverseSearchImageUri != null) {
            baseBind.toolbar.inflateMenu(R.menu.web_reverse_image_search);
            baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    if (reverseSearchImageUri == null) {
                        return true;
                    }
                    if (item.getItemId() == R.id.saucenao) {
                        ReverseImage.reverse(reverseSearchImageUri,
                                ReverseImage.ReverseProvider.SauceNao, new ReverseWebviewCallback(mActivity, reverseSearchImageUri));

                    } else if (item.getItemId() == R.id.ascii2d) {
                        ReverseImage.reverse(reverseSearchImageUri,
                                ReverseImage.ReverseProvider.Ascii2D, new ReverseWebviewCallback(mActivity, reverseSearchImageUri));
                    }
                    return true;
                }
            });
        }
    }

    @Override
    protected void initData() {
        AgentWeb.PreAgentWeb ready = AgentWeb.with(this)
                .setAgentWebParent(baseBind.webViewParent, new RelativeLayout.LayoutParams(-1, -1))
                .useDefaultIndicator()
                .setWebViewClient(new WebViewClient() {
                    @Override
                    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                        if (Dev.use_weiss) {
                            if (handler != null) {
                                handler.proceed();
                            }
                        } else {
                            super.onReceivedSslError(view, handler, error);
                        }
                        Common.showLog(className + "onReceivedSslError " + error.toString());
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        try {
                            String destiny = request.getUrl().toString();
                            Common.showLog(className + "destiny " + destiny);
                            if (destiny.contains(PIXIV_HEAD)) {
                                if (destiny.contains("logout.php") || destiny.contains("settings.php")) {
                                    return false;
                                } else {
                                    try {
                                        Intent intent = new Intent(mContext, OutWakeActivity.class);
                                        intent.setData(Uri.parse(destiny));
                                        startActivity(intent);
                                        if (!preferPreserve) {
                                            finish();
                                        }
                                    } catch (Exception e) {
                                        Common.showToast(e.toString());
                                        e.printStackTrace();
                                    }
                                    return true;
                                }
                            } else if(destiny.contains(ACCOUNT_URL)){
                                try {
                                    String urlForThisAPP = destiny.replace("intent", "shaftintent");
                                    Common.showLog(className + "destiny new " + urlForThisAPP);
                                    Intent intent = new Intent(mContext, OutWakeActivity.class);
                                    intent.setData(Uri.parse(urlForThisAPP));
                                    startActivity(intent);
                                    if (!preferPreserve) {
                                        finish();
                                    }
                                    return true;
                                } catch (Exception e) {
                                    Common.showToast(e.toString());
                                    e.printStackTrace();
                                    return false;
                                }
                            }
                        } catch (Exception e) {
                            Common.showToast(e.toString());
                            e.printStackTrace();
                        }
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        boolean shouldInjectCSS = mContext.getResources().getBoolean(R.bool.is_night_mode) && url.startsWith(PIXIVISION_HEAD);
                        if(shouldInjectCSS){
                            injectCSS();
                        }
                        super.onPageFinished(view, url);
                    }
                })
                .createAgentWeb()
                .ready();

        if (response == null) {
            mAgentWeb = ready.go(url);
            baseBind.ibMenu.setVisibility(View.VISIBLE);
            baseBind.ibMenu.setOnClickListener(v -> mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mWebView.getUrl()))));
        } else {
            baseBind.ibMenu.setVisibility(View.GONE);
            mAgentWeb = ready.get();
            mAgentWeb.getUrlLoader().loadDataWithBaseURL(url, response, mime, encoding, historyUrl);
        }
        Common.showLog(className + url);
        mWebView = mAgentWeb.getWebCreator().getWebView();
        WebSettings settings = mWebView.getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        registerForContextMenu(mWebView);
        // 复制链接文本
        final Handler handler = new LongClickHandler(this);
        mWebView.setOnLongClickListener(v -> {
            final Message message = handler.obtainMessage();
            mWebView.requestFocusNodeHref(message);
            return false;
        });
    }

    private static class LongClickHandler extends Handler {
        private final WeakReference<FragmentWebView> mFragment;

        public LongClickHandler(FragmentWebView fragment){
            mFragment = new WeakReference<>(fragment);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            FragmentWebView fragment = mFragment.get();
            if(fragment != null){
                final Bundle bundle = msg.getData();
                fragment.mLongClickLinkText = String.valueOf(bundle.get("title"));
            }
        }
    }

    @Override
    public void onPause() {
        mAgentWeb.getWebLifeCycle().onPause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mAgentWeb.getWebLifeCycle().onDestroy();
        WeissUtil.end();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        mAgentWeb.getWebLifeCycle().onResume();
        super.onResume();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        WebView.HitTestResult result = mWebView.getHitTestResult();
        mIntentUrl = result.getExtra();
        menu.setHeaderView(new ContextMenuTitleView(mContext, mIntentUrl, Common.resolveThemeAttribute(mContext, R.attr.colorPrimary)));

        if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            mIntentUrl = result.getExtra();
            //menu.setHeaderTitle(mIntentUrl);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_ADDRESS, 1, R.string.webview_handler_copy_link_addr).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_TEXT, 1, R.string.webview_handler_copy_link_text).setOnMenuItemClickListener(handler);
            //menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 1, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SHARE_LINK, 1, R.string.webview_handler_share).setOnMenuItemClickListener(handler);
        }

        if (result.getType() == WebView.HitTestResult.IMAGE_TYPE ||
                result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

            mIntentUrl = result.getExtra();
            //menu.setHeaderTitle(mIntentUrl);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IMAGE, 1, R.string.webview_handler_open_image).setOnMenuItemClickListener(handler);
            //menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 2, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SEARCH_GOOGLE, 2, R.string.webview_handler_search_with_ggl).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SHARE_LINK, 2, R.string.webview_handler_share).setOnMenuItemClickListener(handler);
        }
    }

    public AgentWeb getAgentWeb() {
        return mAgentWeb;
    }

    public void setAgentWeb(AgentWeb agentWeb) {
        mAgentWeb = agentWeb;
    }

    public final class WebViewClickHandler implements MenuItem.OnMenuItemClickListener {
        static final int OPEN_IN_BROWSER = 0x0;
        static final int OPEN_IMAGE = 0x1;
        static final int COPY_LINK_ADDRESS = 0x2;
        static final int COPY_LINK_TEXT = 0x3;
        static final int DOWNLOAD_LINK = 0x4;
        static final int SEARCH_GOOGLE = 0x5;
        static final int SHARE_LINK = 0x6;

        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {

                case OPEN_IN_BROWSER: {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mIntentUrl));
                    mActivity.startActivity(intent);
                    break;
                }
                case OPEN_IMAGE: {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(mIntentUrl), "image/*");
                    mActivity.startActivity(intent);
                    break;
                }
                case COPY_LINK_ADDRESS: {
                    ClipBoardUtils.putTextIntoClipboard(mContext, mIntentUrl);
                    //Snackbar.make(rootView, R.string.copy_to_clipboard, Snackbar.LENGTH_SHORT).show();
                    break;
                }
                case COPY_LINK_TEXT: {
                    ClipBoardUtils.putTextIntoClipboard(mContext, mLongClickLinkText);
                    //Snackbar.make(rootView, R.string.copy_to_clipboard, Snackbar.LENGTH_SHORT).show();
                    break;
                }
                case SEARCH_GOOGLE: {
                    String encodeUrl = mIntentUrl;
                    try {
                        encodeUrl = URLEncoder.encode(mIntentUrl, "utf-8");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    mWebView.loadUrl("https://www.google.com/searchbyimage?image_url=" + encodeUrl);
                    break;
                }
                case SHARE_LINK: {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, mIntentUrl);
                    mActivity.startActivity(Intent.createChooser(intent, mContext.getString(R.string.share)));
                    break;
                }
            }

            return true;
        }
    }


    /**
     * 从contentType中获取MIME类型
     * @param contentType
     * @return
     */
    private String getMime(String contentType) {
        if (contentType == null) {
            return null;
        }
        return contentType.split(";")[0];
    }

    /**
     * 从contentType中获取编码信息
     * @param contentType
     * @return
     */
    private String getCharset(String contentType) {
        if (contentType == null) {
            return null;
        }

        String[] fields = contentType.split(";");
        if (fields.length <= 1) {
            return null;
        }

        String charset = fields[1];
        if (!charset.contains("=")) {
            return null;
        }
        charset = charset.substring(charset.indexOf("=") + 1);
        return charset;
    }

    /**
     * 是否是二进制资源，二进制资源可以不需要编码信息
     * @param mime
     * @return
     */
    private boolean isBinaryRes(String mime) {
        return mime.startsWith("image")
                || mime.startsWith("audio")
                || mime.startsWith("video");
    }

    /**
     * header中是否含有cookie
     * @param headers
     */
    private boolean containCookie(Map<String, String> headers) {
        for (Map.Entry<String, String> headerField : headers.entrySet()) {
            if (headerField.getKey().contains("Cookie")) {
                return true;
            }
        }
        return false;
    }

    public URLConnection recursiveRequest(String path, Map<String, String> headers, String reffer) {
        HttpURLConnection conn;
        URL url = null;
        try {
            url = new URL(path);
            conn = (HttpURLConnection) url.openConnection();
            // 异步接口获取IP
            String ip = "210.140.131.188";
            if (ip != null) {
                // 通过HTTPDNS获取IP成功，进行URL替换和HOST头设置
                Log.d(TAG, "Get IP: " + ip + " for host: " + url.getHost() + " from HTTPDNS successfully!");
                String newUrl = path.replaceFirst(url.getHost(), ip);
                conn = (HttpURLConnection) new URL(newUrl).openConnection();

                if (headers != null) {
                    for (Map.Entry<String, String> field : headers.entrySet()) {
                        conn.setRequestProperty(field.getKey(), field.getValue());
                    }
                }
                // 设置HTTP请求头Host域
                conn.setRequestProperty("Host", url.getHost());
            } else {
                return null;
            }
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            if (conn instanceof HttpsURLConnection) {
                final HttpsURLConnection httpsURLConnection = (HttpsURLConnection)conn;
                // sni场景，创建SSLScocket
                WebviewTlsSniSocketFactory sslSocketFactory = new WebviewTlsSniSocketFactory((HttpsURLConnection) conn);
                httpsURLConnection.setSSLSocketFactory(sslSocketFactory);
                // https场景，证书校验
                httpsURLConnection.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        String host = httpsURLConnection.getRequestProperty("Host");
                        if (null == host) {
                            host = httpsURLConnection.getURL().getHost();
                        }
                        return HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
                    }
                });
            }
            int code = conn.getResponseCode();// Network block
            if (needRedirect(code)) {
                // 原有报头中含有cookie，放弃拦截
                if (containCookie(headers)) {
                    return null;
                }

                String location = conn.getHeaderField("Location");
                if (location == null) {
                    location = conn.getHeaderField("location");
                }

                if (location != null) {
                    if (!(location.startsWith("http://") || location
                            .startsWith("https://"))) {
                        //某些时候会省略host，只返回后面的path，所以需要补全url
                        URL originalUrl = new URL(path);
                        location = originalUrl.getProtocol() + "://"
                                + originalUrl.getHost() + location;
                    }
                    Log.e(TAG, "code:" + code + "; location:" + location + "; path" + path);
                    return recursiveRequest(location, headers, path);
                } else {
                    // 无法获取location信息，让浏览器获取
                    return null;
                }
            } else {
                // redirect finish.
                Log.e(TAG, "redirect finish");
                return conn;
            }
        } catch (MalformedURLException e) {
            Log.w(TAG, "recursiveRequest MalformedURLException");
        } catch (IOException e) {
            Log.w(TAG, "recursiveRequest IOException");
        } catch (Exception e) {
            Log.w(TAG, "unknow exception");
        }
        return null;
    }

    private boolean needRedirect(int code) {
        return code >= 300 && code < 400;
    }

    static class WebviewTlsSniSocketFactory extends SSLSocketFactory {
        private final String TAG = WebviewTlsSniSocketFactory.class.getSimpleName();
        HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        private final HttpsURLConnection conn;

        public WebviewTlsSniSocketFactory(HttpsURLConnection conn) {
            this.conn = conn;
        }

        @Override
        public Socket createSocket() throws IOException {
            return null;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return null;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return null;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return null;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return null;
        }

        // TLS layer

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public Socket createSocket(Socket plainSocket, String host, int port, boolean autoClose) throws IOException {
            String peerHost = this.conn.getRequestProperty("Host");
            if (peerHost == null)
                peerHost = host;
            Log.i(TAG, "customized createSocket. host: " + peerHost);
            InetAddress address = plainSocket.getInetAddress();
            if (autoClose) {
                // we don't need the plainSocket
                plainSocket.close();
            }
            // create and connect SSL socket, but don't do hostname/certificate verification yet
            SSLCertificateSocketFactory sslSocketFactory = (SSLCertificateSocketFactory) SSLCertificateSocketFactory.getDefault(0);
            @SuppressLint("SSLCertificateSocketFactoryCreateSocket") SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(address, port);

            // enable TLSv1.1/1.2 if available
            ssl.setEnabledProtocols(ssl.getSupportedProtocols());

            // set up SNI before the handshake
            Log.i(TAG, "Setting SNI hostname");
            sslSocketFactory.setHostname(ssl, peerHost);

            // verify hostname and certificate
            SSLSession session = ssl.getSession();

            if (!hostnameVerifier.verify(peerHost, session))
                throw new SSLPeerUnverifiedException("Cannot verify hostname: " + peerHost);

            Log.i(TAG, "Established " + session.getProtocol() + " connection with " + session.getPeerHost() +
                    " using " + session.getCipherSuite());

            return ssl;
        }
    }

    private void injectCSS() {
        try {
            InputStream inputStream = mContext.getAssets().open("pixivision-dark.css");
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            String encoded = Base64.encodeToString(buffer, Base64.NO_WRAP);
            mWebView.loadUrl("javascript:(function() {" +
                    "var parent = document.getElementsByTagName('head').item(0);" +
                    "var style = document.createElement('style');" +
                    "style.type = 'text/css';" +
                    // Tell the browser to BASE64-decode the string into your script !!!
                    "style.innerHTML = window.atob('" + encoded + "');" +
                    "parent.appendChild(style)" +
                    "})()");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
