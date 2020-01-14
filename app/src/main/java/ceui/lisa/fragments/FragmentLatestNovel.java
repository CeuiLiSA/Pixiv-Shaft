package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

public class FragmentLatestNovel extends NetListFragment<FragmentBaseListBinding, ListNovel,
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
    public NetControl<ListNovel> present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getNewNovels(Shaft.sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }
}
