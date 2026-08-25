package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import ceui.lisa.R;
import ceui.lisa.databinding.FragmentMarkdownBinding;
import ceui.lisa.http.LegacyApiCalls;
import ceui.lisa.http.ResourceApi;
import ceui.lisa.utils.Params;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.LinkResolverDef;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;

public class FragmentMarkdown extends BaseFragment<FragmentMarkdownBinding> {

    private String url;

    @Override
    protected void initBundle(Bundle bundle) {
        super.initBundle(bundle);
        url = bundle.getString(Params.URL);
    }

    public static FragmentMarkdown newInstance(String url) {
        Bundle args = new Bundle();
        args.putString(Params.URL, url);
        FragmentMarkdown fragment = new FragmentMarkdown();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_markdown;
    }

    @Override
    protected void initData() {
        super.initData();
        LegacyApiCalls.getResourceText(this, url, content -> {
            if (TextUtils.isEmpty(content)) {
                return;
            }

            final Markwon markwon = Markwon.builder(mContext)
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
                            builder.linkResolver(new LinkResolverDef() {
                                @Override
                                public void resolve(@NonNull View view, @NonNull String link) {
                                    if (link.startsWith("./")) {
                                        String newLink = ResourceApi.JSDELIVR_BASE_URL + ResourceApi.JSDELIVR_PROJECT_MASTER_PATH + link;
                                        super.resolve(view, newLink);
                                    } else {
                                        super.resolve(view, link);
                                    }
                                }
                            });
                        }
                    }).build();
            markwon.setMarkdown(baseBind.text, content);
        }, null);
    }
}
