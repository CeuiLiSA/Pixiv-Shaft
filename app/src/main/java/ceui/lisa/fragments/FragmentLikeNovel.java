package ceui.lisa.fragments;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.model.ListNovelResponse;
import ceui.lisa.model.NovelBean;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人收藏的小说
 */
public class FragmentLikeNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovelResponse, NovelBean, RecyNovelBinding> {

    private int userID;
    private String starType;
    private boolean showToolbar = false;

    public static FragmentLikeNovel newInstance(int userID, String starType) {
        FragmentLikeNovel fragmentRelatedIllust = new FragmentLikeNovel();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.starType = starType;
        return fragmentRelatedIllust;
    }

    public static FragmentLikeNovel newInstance(int userID, String starType, boolean paramShowToolbar) {
        FragmentLikeNovel fragmentRelatedIllust = new FragmentLikeNovel();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.starType = starType;
        fragmentRelatedIllust.showToolbar = paramShowToolbar;
        return fragmentRelatedIllust;
    }

    @Override
    public NetControl<ListNovelResponse> present() {
        return new NetControl<ListNovelResponse>() {
            @Override
            public Observable<ListNovelResponse> initApi() {
                return Retro.getAppApi().getUserLikeNovel(sUserModel
                        .getResponse().getAccess_token(), userID, starType);
            }

            @Override
            public Observable<ListNovelResponse> initNextApi() {
                return Retro.getAppApi().getNextNovel(sUserModel.getResponse().getAccess_token(), nextUrl);
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
