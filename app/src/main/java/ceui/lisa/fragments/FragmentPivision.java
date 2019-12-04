package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.AAdapter;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyArticalBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ArticleResponse;
import ceui.lisa.model.SpotlightArticlesBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentPivision extends NetListFragment<FragmentBaseListBinding,
        ArticleResponse, SpotlightArticlesBean, RecyArticalBinding> {

    private String dataType;

    public static FragmentPivision newInstance(String dataType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, dataType);
        FragmentPivision fragment = new FragmentPivision();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        dataType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.pixiv_special);
    }

    @Override
    public NetControl<ArticleResponse> present() {
        return new NetControl<ArticleResponse>() {
            @Override
            public Observable<ArticleResponse> initApi() {
                return Retro.getAppApi().getArticles(sUserModel.getResponse().getAccess_token(), dataType);
            }

            @Override
            public Observable<ArticleResponse> initNextApi() {
                return Retro.getAppApi().getNextArticals(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<SpotlightArticlesBean, RecyArticalBinding> adapter() {
        return new AAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", allItems.get(position).getArticle_url());
                intent.putExtra("title", getString(R.string.pixiv_special));
                startActivity(intent);
            }
        });
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
    }
}
