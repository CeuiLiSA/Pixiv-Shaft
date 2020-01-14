package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

public class FragmentLatestWorks extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean, RecyIllustStaggerBinding> {

    private String workType;

    public static FragmentLatestWorks newInstance(String paramWorkType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, paramWorkType);
        FragmentLatestWorks fragment = new FragmentLatestWorks();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        workType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public NetControl<ListIllust> present() {
        return new NetControl<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getNewWorks(Shaft.sUserModel.getResponse().getAccess_token(), workType);
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }
}
