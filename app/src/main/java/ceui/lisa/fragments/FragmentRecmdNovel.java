package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.NovelHorizontalAdapter;
import ceui.lisa.adapters.RAdapter;
import ceui.lisa.databinding.FragmentRecmdBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.ListNovelResponse;
import ceui.lisa.model.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecorationNoLRTB;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

public class FragmentRecmdNovel extends NetListFragment<FragmentRecmdBinding,
        ListNovelResponse, NovelBean, RecyNovelBinding> {

    @Override
    public NetControl<ListNovelResponse> present() {
        return new NetControl<ListNovelResponse>() {
            @Override
            public Observable<ListNovelResponse> initApi() {
                return Retro.getAppApi().getRecmdNovel(Shaft.sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListNovelResponse> initNextApi() {
                return Retro.getAppApi().getNextNovel(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.NOVEL_ID, allItems.get(position).getId());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                startActivity(intent);
            }
        });
    }

    @Override
    public void initView(View view) {
        super.initView(view);
        baseBind.seeMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                intent.putExtra("dataType", "小说");
                startActivity(intent);
            }
        });
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_recmd;
    }

    @Override
    public String getToolbarTitle() {
        return "推荐小说";
    }

    @Override
    public void firstSuccess() {
        List<NovelBean> ranking = new ArrayList<>(mResponse.getRanking_novels());
        baseBind.ranking.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        baseBind.ranking.setLayoutManager(manager);
        baseBind.ranking.setHasFixedSize(true);
        NovelHorizontalAdapter adapter = new NovelHorizontalAdapter(ranking, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.NOVEL_ID, ranking.get(position).getId());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                startActivity(intent);
            }
        });
        baseBind.ranking.setAdapter(adapter);
    }
}
