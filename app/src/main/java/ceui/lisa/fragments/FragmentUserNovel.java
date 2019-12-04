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
 * 某人创作的小说
 */
public class FragmentUserNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovelResponse, NovelBean, RecyNovelBinding> {

    private int userID;
    private boolean showToolbar = false;

    public static FragmentUserNovel newInstance(int userID, boolean paramShowToolbar) {
        FragmentUserNovel fragmentRelatedIllust = new FragmentUserNovel();
        fragmentRelatedIllust.userID = userID;
        fragmentRelatedIllust.showToolbar = paramShowToolbar;
        return fragmentRelatedIllust;
    }

    @Override
    public NetControl<ListNovelResponse> present() {
        return new NetControl<ListNovelResponse>() {
            @Override
            public Observable<ListNovelResponse> initApi() {
                return Retro.getAppApi().getUserSubmitNovel(sUserModel
                        .getResponse().getAccess_token(), userID);
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
        return showToolbar ? "小说作品" : super.getToolbarTitle();
    }
}
