package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.ListNovelResponse;
import ceui.lisa.model.NovelBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

public class FragmentLatestNovel extends NetListFragment<FragmentBaseListBinding, ListNovelResponse,
        NovelBean, RecyNovelBinding> {

    private String workType;

    public static FragmentLatestNovel newInstance(String paramWorkType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, paramWorkType);
        FragmentLatestNovel fragment = new FragmentLatestNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        workType = bundle.getString(Params.DATA_TYPE);
    }


    @Override
    public NetControl<ListNovelResponse> present() {
        return new NetControl<ListNovelResponse>() {
            @Override
            public Observable<ListNovelResponse> initApi() {
                return Retro.getAppApi().getNewNovels(Shaft.sUserModel.getResponse().getAccess_token());
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
    public boolean showToolbar() {
        return false;
    }
}
