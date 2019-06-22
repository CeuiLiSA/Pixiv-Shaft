package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ProgressBar;

import com.github.ybq.android.spinkit.style.DoubleBounce;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.adapters.PivisionHorizontalAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ArticalResponse;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * Pivision 文章
 */
public class FragmentPivisionHorizontal extends BaseFragment {

    private ProgressBar mProgressBar;
    private RecyclerView mRecyclerView;
    private List<ArticalResponse.SpotlightArticlesBean> allItems = new ArrayList<>();
    private PivisionHorizontalAdapter mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_pivision_horizontal;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        DoubleBounce doubleBounce = new DoubleBounce();
        doubleBounce.setColor(getResources().getColor(R.color.white));
        mProgressBar.setIndeterminateDrawable(doubleBounce);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRecyclerView.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        v.findViewById(R.id.see_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "特辑");
                startActivity(intent);
            }
        });
        return v;
    }

    @Override
    void initData() {
        getFirstData();
    }

    private void getFirstData(){
        Retro.getAppApi().getArticals(sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<ArticalResponse>() {
                    @Override
                    public void onNext(ArticalResponse articalResponse) {
                        if(articalResponse != null){
                            allItems.clear();
                            allItems.addAll(articalResponse.getList());
                            mAdapter = new PivisionHorizontalAdapter(allItems, mContext);
                            mAdapter.setOnItemClickListener(new OnItemClickListener() {
                                @Override
                                public void onItemClick(View v, int position, int viewType) {
                                    Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                                    intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                                    intent.putExtra("url", allItems.get(position).getArticle_url());
                                    startActivity(intent);
                                }
                            });
                            mRecyclerView.setAdapter(mAdapter);
                        }
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
