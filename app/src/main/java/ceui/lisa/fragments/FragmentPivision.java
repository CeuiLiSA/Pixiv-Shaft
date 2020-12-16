package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.ArticleAdapter;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyArticalBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListArticle;
import ceui.lisa.models.SpotlightArticlesBean;
import ceui.lisa.repo.PivisionRepo;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;

public class FragmentPivision extends NetListFragment<FragmentBaseListBinding,
        ListArticle, SpotlightArticlesBean> {

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
    public RemoteRepo<ListArticle> repository() {
        return new PivisionRepo(dataType, false){
            @Override
            public boolean localData() {
                return false;
            }
        };
    }

    @Override
    public BaseAdapter<SpotlightArticlesBean, RecyArticalBinding> adapter() {
        return new ArticleAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra(Params.URL, allItems.get(position).getArticle_url());
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_special));
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
