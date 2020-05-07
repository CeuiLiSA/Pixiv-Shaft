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
        NovelBean> {

    @Override
    public NetControl<ListNovel> present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getNewNovels(Shaft.sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(Shaft.sUserModel.getResponse().getAccess_token(),
                        mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(mModel.getContent().getValue(), mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }
}
