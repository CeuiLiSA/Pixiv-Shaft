package ceui.lisa.fragments;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人收藏的小说
 */
public class FragmentLikeNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean, RecyNovelBinding> {

    private int userID;
    private String starType;
    private boolean showToolbar = false;

    public static FragmentLikeNovel newInstance(int userID, String starType, boolean paramShowToolbar) {
        FragmentLikeNovel fragmentRelatedIllust = new FragmentLikeNovel();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.starType = starType;
        fragmentRelatedIllust.showToolbar = paramShowToolbar;
        return fragmentRelatedIllust;
    }

    @Override
    public NetControl<ListNovel> present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getUserLikeNovel(sUserModel
                        .getResponse().getAccess_token(), userID, starType);
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(
                        sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return showToolbar ? "小说收藏" : super.getToolbarTitle();
    }
}
