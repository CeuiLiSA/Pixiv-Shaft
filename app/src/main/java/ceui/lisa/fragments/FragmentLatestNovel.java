package ceui.lisa.fragments;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import io.reactivex.Observable;

public class FragmentLatestNovel extends NetListFragment<FragmentBaseListBinding, ListNovel,
        NovelBean> {

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new RemoteRepo<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getNewNovels(token());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(token(), mModel.getNextUrl());
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
