package ceui.lisa.fragments;

import android.support.v7.widget.Toolbar;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.RelativeLayout;

import com.just.agentweb.AgentWeb;
import com.just.agentweb.WebViewClient;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class FragmentWebView extends BaseFragment {

    private String title;
    private String url;
    private AgentWeb mAgentWeb;
    private RelativeLayout webViewParent;

    public static FragmentWebView newInstance(String title, String url) {
        FragmentWebView fragmentWebView = new FragmentWebView();
        fragmentWebView.title = title;
        fragmentWebView.url = url;
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
        mAgentWeb = AgentWeb.with(this)
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
                .ready()
                .go(url);
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
