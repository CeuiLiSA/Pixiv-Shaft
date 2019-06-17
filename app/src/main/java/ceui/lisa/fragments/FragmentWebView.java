package ceui.lisa.fragments;

import android.support.v7.widget.Toolbar;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebViewClient;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.view.ContextMenuTitleView;
import ceui.lisa.utils.WebViewClickHandler;

public class FragmentWebView extends BaseFragment {

    private String title;
    private String url;
    private String response = null;
    private String mime = null;
    private String encoding = null;
    private String history_url = null;
    private AgentWeb mAgentWeb;
    private WebView mWebView;
    private RelativeLayout webViewParent;
    private String mImageUrl;
    private WebViewClickHandler handler = new WebViewClickHandler(getContext(),webViewParent);

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
                        String destiny = request.getUrl().toString();
                        Common.showLog(className + destiny);
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        Common.showLog("requesting " + request.getUrl());
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

        if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            mImageUrl = result.getExtra();
            menu.setHeaderView(new ContextMenuTitleView(getContext(), mImageUrl));
            //menu.setHeaderTitle(mImageUrl);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_ADDRESS, 1, R.string.webview_handler_copy_link_addr).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.COPY_LINK_TEXT, 1, R.string.webview_handler_copy_link_text).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 1, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SHARE_LINK, 1, R.string.webview_handler_share).setOnMenuItemClickListener(handler);
        }

        if (result.getType() == WebView.HitTestResult.IMAGE_TYPE ||
                result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

            mImageUrl = result.getExtra();
            //menu.setHeaderTitle(mImageUrl);
            menu.setHeaderView(new ContextMenuTitleView(getContext(), mImageUrl));
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IN_BROWSER, 0, R.string.webview_handler_open_in_browser).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.OPEN_IMAGE, 1, R.string.webview_handler_open_image).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.DOWNLOAD_LINK, 2, R.string.webview_handler_download_link).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SEARCH_GOOGLE, 2, R.string.webview_handler_search_with_ggl).setOnMenuItemClickListener(handler);
            menu.add(Menu.NONE, WebViewClickHandler.SHARE_LINK, 2, R.string.webview_handler_share).setOnMenuItemClickListener(handler);

        }
    }


}
