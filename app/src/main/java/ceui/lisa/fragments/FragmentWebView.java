package ceui.lisa.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebChromeClient;
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

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BundleCompat;
import androidx.appcompat.widget.Toolbar;
import ceui.lisa.R;
import android.webkit.CookieManager;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.databinding.FragmentWebviewBinding;
import ceui.lisa.feature.WeissUtil;
import ceui.lisa.http.HttpDns;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.view.ContextMenuTitleView;
import ceui.pixiv.session.SessionManager;
import com.tencent.mmkv.MMKV;

import static android.app.Activity.RESULT_OK;

public class FragmentWebView extends BaseFragment<FragmentWebviewBinding> {

    //private static final String ILLUST_HEAD = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=";
    private static final String USER_HEAD = "https://www.pixiv.net/member.php?id=";
    private static final String WORKS_HEAD = "https://www.pixiv.net/artworks/";
    private static final String PIXIV_HEAD = "https://www.pixiv.net/";
    private static final String ACCOUNT_URL = "intent://account/";
    private static final String PIXIVISION_HEAD = "https://www.pixivision.net/";
    private static final String LOGIN_SIGN_HEAD = "https://app-api.pixiv.net/web";
    private static final String TAG = "FragmentWebView";
    private String title;
    private String url;
    private boolean preferPreserve = false;
    private AgentWeb mAgentWeb;
    private WebView mWebView;
    private String mIntentUrl;
    private final WebViewClickHandler handler = new WebViewClickHandler();
    private final HttpDns httpDns = HttpDns.getInstance();
    private String mLongClickLinkText;
    private Uri reverseSearchImageUri;
    /**
     * 图搜：这张图还没喂给页面。用户点页面自己的上传按钮时顶上去，顶过一次就落下，
     * 之后再点走正常的系统选择器。
     */
    private boolean reverseUploadArmed = false;
    private ValueCallback<Uri> uploadMessage;
    private ValueCallback<Uri[]> uploadMessageAboveL;
    /**
     * 返回键/手势先退网页历史(AgentWeb.back 还顺带退全屏视频)。enabled 只在网页真有历史可退
     * 时为 true(doUpdateVisitedHistory / onPageFinished 里刷新):没历史时不拦,系统自己
     * finish 宿主并播预测式返回动画 —— 以前这段逻辑挂在 TemplateActivity 的常开兜底 callback
     * 里,把全 app 的预测式返回都掐掉了。
     */
    private final OnBackPressedCallback webBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mAgentWeb != null && mAgentWeb.back()) {
                syncBackCallback();
                return;
            }
            setEnabled(false);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    };

    private void syncBackCallback() {
        webBackCallback.setEnabled(mWebView != null && mWebView.canGoBack());
    }

    @Override
    public void initBundle(Bundle bundle) {
        title = bundle.getString(Params.TITLE);
        url = bundle.getString(Params.URL);
        preferPreserve = bundle.getBoolean(Params.PREFER_PRESERVE);
        reverseSearchImageUri = BundleCompat.getParcelable(
                bundle, Params.REVERSE_SEARCH_IMAGE_URI, Uri.class);
        reverseUploadArmed = reverseSearchImageUri != null;
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

    /**
     * 以图搜图：url 是引擎的上传页，用户点页面上的选择文件按钮时，imageUri 会直接顶上去，
     * 不弹系统选择器让他重挑一遍。见 {@link ReverseImage} 里为什么上传必须由 WebView 自己发。
     */
    public static FragmentWebView newInstance(String title, String url, Uri imageUri) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        args.putString(Params.URL, url);
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
                        ReverseImage.search(mActivity, reverseSearchImageUri,
                                ReverseImage.ReverseProvider.SauceNao);
                    } else if (item.getItemId() == R.id.ascii2d) {
                        ReverseImage.search(mActivity, reverseSearchImageUri,
                                ReverseImage.ReverseProvider.Ascii2D);
                    }
                    return true;
                }
            });
        }
    }

    @Override
    protected void initData() {
        AgentWeb.PreAgentWeb ready;
        try {
            ready = createAgentWeb();
        } catch (Exception e) {
            // 设备上的 System WebView 被停用/卸载/正在更新时,AgentWeb 内部 new WebView()
            // 直接抛(MissingWebViewPackageException 之类)。BaseFragment#onCreateView 会把它
            // 吞掉,mAgentWeb 就一直是 null——白页还不算完,紧接着的 onResume 解引用它,
            // 把整个 TemplateActivity 崩在 performResumeActivity 上。这里当场收场。
            e.printStackTrace();
            Common.showToast(getString(R.string.msg_no_webview));
            finish();
            return;
        }

        // 注入已同步的 Cookie，确保 pixiv 设置页等需要登录的页面能正常加载
        String savedCookies = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "");
        if (savedCookies != null && !savedCookies.isEmpty()) {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            for (String cookie : savedCookies.split(";")) {
                cookieManager.setCookie(url, cookie.trim());
            }
            cookieManager.flush();
        }

        mAgentWeb = ready.go(url);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), webBackCallback);
        baseBind.ibMenu.setVisibility(View.VISIBLE);
        baseBind.ibMenu.setOnClickListener(v -> {
            String jumpUrl = url.contains(LOGIN_SIGN_HEAD) ? url : mWebView.getUrl();
            try {
                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(jumpUrl)));
            } catch (ActivityNotFoundException e) {
                Common.showToast(getString(R.string.msg_no_browser));
            }
        });
        Common.showLog(className + url);
        mWebView = mAgentWeb.getWebCreator().getWebView();
        // 放行第三方 cookie(WebView 默认屏蔽,和 WebFragment / StreetMainFragment 对齐)。
        // 图搜的 Cloudflare Turnstile 跑在 challenges.cloudflare.com 的 iframe 里,对引擎域
        // 是第三方——存不下验证状态就会「勾完真人框又弹回来」无限循环(#733 真机复现)。
        CookieManager.getInstance().setAcceptThirdPartyCookies(mWebView, true);
        // 返回键会一路退网页历史(见 webBackCallback),
        // 退过头了就靠这个回去 —— 图搜搜半天被一次误触返回抹掉太亏(#733)。
        baseBind.ibForward.setOnClickListener(v -> {
            if (mWebView.canGoForward()) {
                mWebView.goForward();
            }
        });
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
        mWebView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // 图搜：图片是调用方带进来的，直接顶上去，别再弹系统选择器让用户重挑一遍。
                //
                // 这一步曾经想用 JS 自动完成（onPageFinished 里注入 input.click() 顺带挂 change
                // 自动提交），真机验证证伪：Chromium 要求用户手势才肯开文件选择器，注入的 click
                // 拿不到手势，选择器根本不弹，后面的 change 自动提交也就没机会跑。那段 JS 只留下
                // 站点特定的脆弱选择器，已删。现在老老实实等用户点页面自己的按钮。
                if (reverseUploadArmed && reverseSearchImageUri != null) {
                    reverseUploadArmed = false;
                    filePathCallback.onReceiveValue(new Uri[]{reverseSearchImageUri});
                    return true;
                }
                uploadMessageAboveL = filePathCallback;
                openImageChooserActivity();
                return true;
            }
        });
    }

    private AgentWeb.PreAgentWeb createAgentWeb() {
        return AgentWeb.with(this)
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
                                if (destiny.contains("logout.php") || destiny.contains("login.php") || destiny.contains("settings.php") || destiny.contains("/settings/") || destiny.contains("upload.php")) {
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
                        syncForwardButton();
                        syncBackCallback();
                        super.onPageFinished(view, url);
                    }

                    @Override
                    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                        super.doUpdateVisitedHistory(view, url, isReload);
                        syncForwardButton();
                        syncBackCallback();
                    }
                })
                .createAgentWeb()
                .ready();
    }

    /**
     * 「前进」只在网页真有前进历史时露头，免得平时挂一个永远点不动的按钮。
     *
     * <p>只由 {@code onPageFinished} 驱动：{@code goForward()} 是异步的，紧跟着读
     * {@code canGoForward()} 拿到的是旧值，等页面落地再同步才准。前进/后退都会走到
     * {@code onPageFinished}，所以退过头时按钮会自己冒出来。</p>
     */
    private void syncForwardButton() {
        if (mWebView == null) {
            return;
        }
        baseBind.ibForward.setVisibility(mWebView.canGoForward() ? View.VISIBLE : View.GONE);
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

    // WebView 建不起来时 mAgentWeb 会一直是 null(见 initData)。上面的 finish() 只是排队,
    // 本轮的 onResume / onPause 照样会走到这里,所以三个回调都得先判空。
    @Override
    public void onPause() {
        if (mAgentWeb != null) {
            mAgentWeb.getWebLifeCycle().onPause();
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mAgentWeb != null) {
            mAgentWeb.getWebLifeCycle().onDestroy();
        }
        WeissUtil.end();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        if (mAgentWeb != null) {
            mAgentWeb.getWebLifeCycle().onResume();
        }
        super.onResume();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        WebView.HitTestResult result = mWebView.getHitTestResult();
        mIntentUrl = result.getExtra();
        menu.setHeaderView(new ContextMenuTitleView(mContext, mIntentUrl, Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary)));

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
                    try {
                        mActivity.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Common.showToast(getString(R.string.msg_no_browser));
                    }
                    break;
                }
                case OPEN_IMAGE: {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(mIntentUrl), "image/*");
                    try {
                        mActivity.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Common.showToast(getString(R.string.msg_no_browser));
                    }
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
            String ip = "210.140.139.157";
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

    private void openImageChooserActivity() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Image Chooser"), Params.REQUEST_CODE_CHOOSE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE) {
            if (null == uploadMessage && null == uploadMessageAboveL) return;
            Uri result = data == null || resultCode != RESULT_OK ? null : data.getData();
            if (uploadMessageAboveL != null) {
                onActivityResultAboveL(requestCode, resultCode, data);
            } else if (uploadMessage != null) {
                uploadMessage.onReceiveValue(result);
                uploadMessage = null;
            }
        }
    }

    private void onActivityResultAboveL(int requestCode, int resultCode, Intent intent) {
        if (requestCode != Params.REQUEST_CODE_CHOOSE || uploadMessageAboveL == null)
            return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (intent != null) {
                String dataString = intent.getDataString();
                ClipData clipData = intent.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        ClipData.Item item = clipData.getItemAt(i);
                        results[i] = item.getUri();
                    }
                }
                if (dataString != null)
                    results = new Uri[]{Uri.parse(dataString)};
            }
        }
        uploadMessageAboveL.onReceiveValue(results);
        uploadMessageAboveL = null;
    }
}
