package ceui.lisa.fragments;

import android.support.v7.widget.Toolbar;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.IUrlLoader;
import com.just.agentweb.WebViewClient;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class FragmentWebView extends BaseFragment {

    private String title;
    private String url;
    private String response = null;
    private String mime = null;
    private String encoding = null;
    private String history_url = null;
    private AgentWeb mAgentWeb;
    private RelativeLayout webViewParent;

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
                })
                .createAgentWeb()
                .ready();

        if (response == null) {
            mAgentWeb = ready.go(url);
        } else {
            mAgentWeb = ready.get();
            mAgentWeb.getUrlLoader().loadDataWithBaseURL(url, response, mime, encoding, history_url);
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
        super.onDestroy();
    }

    @Override
    public void onResume() {
        mAgentWeb.getWebLifeCycle().onResume();
        super.onResume();
    }
}
