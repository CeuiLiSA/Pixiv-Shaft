package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebViewClient;

import java.util.Objects;

import ceui.lisa.R;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.download.WebDownload;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.ContextMenuTitleView;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentWebView extends BaseFragment {

    private static final String ILLUST_HEAD = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=";
    private static final String USER_HEAD = "https://www.pixiv.net/member.php?id=";
    private String title;
    private String url;
    private String response = null;
    private String mime = null;
    private String encoding = null;
    private String history_url = null;
    private AgentWeb mAgentWeb;
    private WebView mWebView;
    private RelativeLayout webViewParent;
    private String mIntentUrl;
    private WebViewClickHandler handler = new WebViewClickHandler();

    public static FragmentWebView newInstance(String title, String url) {
        FragmentWebView fragmentWebView = new FragmentWebView();
        fragmentWebView.title = title;
        fragmentWebView.url = url;
        return fragmentWebView;
    }

    /**
     * Loads with local html source
     *
     * @param title
     * @param url
     * @param response
     * @param mime
     * @param encoding
     * @param history_url
     * @return
     */
    public static FragmentWebView newInstance(String title, String url, String response, String mime, String encoding, String history_url) {
        FragmentWebView fragmentWebView = newInstance(title, url);
        fragmentWebView.response = response;
        fragmentWebView.mime = mime;
        fragmentWebView.encoding = encoding;
        fragmentWebView.history_url = history_url;
        return fragmentWebView;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_webview;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());
        webViewParent = v.findViewById(R.id.web_view_parent);
        return v;
    }

    @Override
    void initData() {
        AgentWeb.PreAgentWeb ready = AgentWeb.with(this)
                .setAgentWebParent(webViewParent, new RelativeLayout.LayoutParams(-1, -1))
                .useDefaultIndicator()
                .setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {

                        //点击画作 https://www.pixiv.net/member_illust.php?mode=medium&illust_id=70374965
                        String destiny = request.getUrl().toString();
                        Common.showLog(className + destiny);
                        if (destiny.contains(ILLUST_HEAD)) {
                            Common.showLog("点击了ILLUST， 拦截调回APP");
                            PixivOperate.getIllustByID(sUserModel,
                                    Integer.valueOf(destiny.substring(ILLUST_HEAD.length())), mContext);
                            return true;
                        }

                        if (destiny.contains(USER_HEAD)) {
                            Common.showLog("点击了USER， 拦截调回APP");
                            Intent intent = new Intent(mContext, UserDetailActivity.class);
                            intent.putExtra("user id", Integer.valueOf(destiny.substring(USER_HEAD.length())));
                            startActivity(intent);
                            return true;
                        }

                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        Log.i("WebView", String.format("requesting %s", request.getUrl()));
                        return super.shouldInterceptRequest(view, request);
                    }
                })
                .createAgentWeb()
                .ready();

        if (response == null) {
            mAgentWeb = ready.go(url);
        } else {
            mAgentWeb = ready.get();
            mAgentWeb.getUrlLoader().loadDataWithBaseURL(url, response, mime, encoding, history_url);
        }

        mWebView = mAgentWeb.getWebCreator().getWebView();
        WebSettings settings = mWebView.getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        registerForContextMenu(mWebView);
    }

    @Override
    public void onPause() {
        mAgentWeb.getWebLifeCycle().onPause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mAgentWeb.getWebLifeCycle().onDestroy();
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
        menu.setHeaderView(new ContextMenuTitleView(getContext(), mIntentUrl));

        if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            mIntentUrl = result.getExtra();
            //menu.setHeaderTitle(mIntentUrl);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_ADDRESS, 1, R.string.webview_handler_copy_link_addr).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_TEXT, 1, R.string.webview_handler_copy_link_text).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 1, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SHARE_LINK, 1, R.string.webview_handler_share).setOnMenuItemClickListener(handler);
        }

        if (result.getType() == WebView.HitTestResult.IMAGE_TYPE ||
                result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

            mIntentUrl = result.getExtra();
            //menu.setHeaderTitle(mIntentUrl);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IMAGE, 1, R.string.webview_handler_open_image).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 2, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
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

    public class WebViewClickHandler implements MenuItem.OnMenuItemClickListener {
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
                    Objects.requireNonNull(getActivity()).startActivity(intent);
                    break;
                }
                case OPEN_IMAGE: {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(mIntentUrl), "image/*");
                    Objects.requireNonNull(getActivity()).startActivity(intent);
                    break;
                }
                case COPY_LINK_ADDRESS: {
                    ClipBoardUtils.putTextIntoClipboard(mContext, mIntentUrl);
                    Snackbar.make(parentView, R.string.copy_to_clipboard, Snackbar.LENGTH_SHORT).show();
                    break;
                }
                case COPY_LINK_TEXT: {
                    Common.showToast("不会");
                    break;
                }
                case DOWNLOAD_LINK: {
                    WebDownload.download(mIntentUrl);
                    break;
                }
                case SEARCH_GOOGLE: {
                    mWebView.loadUrl("https://www.google.com/searchbyimage?image_url=" + mIntentUrl);
                    break;
                }
                case SHARE_LINK: {
                    Intent intent = new Intent(Intent.ACTION_SEND, Uri.parse(mIntentUrl));
                    Objects.requireNonNull(getActivity()).startActivity(intent);
                    break;
                }
            }

            return true;
        }
    }
}
