package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.google.android.material.snackbar.Snackbar;
import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebViewClient;

import ceui.lisa.R;
import ceui.lisa.activities.OutWakeActivity;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.databinding.FragmentWebviewBinding;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.ContextMenuTitleView;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentWebView extends BaseFragment<FragmentWebviewBinding> {

    //private static final String ILLUST_HEAD = "https://www.pixiv.net/member_illust.php?mode=medium&illust_id=";
    private static final String USER_HEAD = "https://www.pixiv.net/member.php?id=";
    private static final String WORKS_HEAD = "https://www.pixiv.net/artworks/";
    private static final String PIXIV_HEAD = "https://www.pixiv.net/";
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
    private WebViewClickHandler handler = new WebViewClickHandler();

    @Override
    public void initBundle(Bundle bundle) {
        title = bundle.getString(Params.TITLE);
        url = bundle.getString(Params.URL);
        response = bundle.getString(Params.RESPONSE);
        mime = bundle.getString(Params.MIME);
        encoding = bundle.getString(Params.ENCODING);
        historyUrl = bundle.getString(Params.HISTORY_URL);
        preferPreserve = bundle.getBoolean(Params.PREFER_PRESERVE);
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

    public static FragmentWebView newInstance(String title, String url, String response,
                                              String mime, String encoding, String history_url) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        args.putString(Params.URL, url);
        args.putString(Params.RESPONSE, response);
        args.putString(Params.MIME, mime);
        args.putString(Params.ENCODING, encoding);
        args.putString(Params.HISTORY_URL, history_url);
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
    }

    @Override
    protected void initData() {
        AgentWeb.PreAgentWeb ready = AgentWeb.with(this)
                .setAgentWebParent(baseBind.webViewParent, new RelativeLayout.LayoutParams(-1, -1))
                .useDefaultIndicator()
                .setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {

                        String destiny = request.getUrl().toString();
                        Common.showLog(className + "destiny " + destiny);
                        if (destiny.contains(PIXIV_HEAD)) {
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

                        return super.shouldOverrideUrlLoading(view, request);
                    }
                })
                .createAgentWeb()
                .ready();

        if (response == null) {
            mAgentWeb = ready.go(url);
        } else {
            mAgentWeb = ready.get();
            mAgentWeb.getUrlLoader().loadDataWithBaseURL(url, response, mime, encoding, historyUrl);
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
        menu.setHeaderView(new ContextMenuTitleView(mContext, mIntentUrl, Common.resolveThemeAttribute(mContext, R.attr.colorPrimary)));

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
                    Snackbar.make(rootView, R.string.copy_to_clipboard, Snackbar.LENGTH_SHORT).show();
                    break;
                }
                case COPY_LINK_TEXT: {
                    Common.showToast("不会");
                    break;
                }
                case SEARCH_GOOGLE: {
                    mWebView.loadUrl("https://www.google.com/searchbyimage?image_url=" + mIntentUrl);
                    break;
                }
                case SHARE_LINK: {
                    Intent intent = new Intent(Intent.ACTION_SEND, Uri.parse(mIntentUrl));
                    mActivity.startActivity(intent);
                    break;
                }
            }

            return true;
        }
    }
}
