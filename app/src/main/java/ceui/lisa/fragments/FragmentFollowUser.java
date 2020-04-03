package ceui.lisa.fragments;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentFollowUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean, RecyUserPreviewBinding> {

    private int userID;
    private String starType;
    private boolean showToolbar = false;

    public static FragmentFollowUser newInstance(int userID, String starType, boolean pShowToolbar) {
        FragmentFollowUser followUser = new FragmentFollowUser();
        followUser.userID = userID;
        followUser.starType = starType;
        followUser.showToolbar = pShowToolbar;
        return followUser;
    }

    @Override
    public NetControl<ListUser> present() {
        return new NetControl<ListUser>() {
            @Override
            public Observable<ListUser> initApi() {
                return Retro.getAppApi().getFollowUser(sUserModel.getResponse().getAccess_token(), userID, starType);
            }

            @Override
            public Observable<ListUser> initNextApi() {
                return Retro.getAppApi().getNextUser(sUserModel.getResponse().getAccess_token(),
                        mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return "关注";
    }
}
