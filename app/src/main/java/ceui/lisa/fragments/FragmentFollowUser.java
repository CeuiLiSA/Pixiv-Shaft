package ceui.lisa.fragments;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.UserPreviewsBean;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentFollowUser extends NetListFragment<FragmentBaseListBinding,
        ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

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
    public NetControl<ListUserResponse> present() {
        return new NetControl<ListUserResponse>() {
            @Override
            public Observable<ListUserResponse> initApi() {
                return Retro.getAppApi().getFollowUser(sUserModel.getResponse().getAccess_token(), userID, starType);
            }

            @Override
            public Observable<ListUserResponse> initNextApi() {
                return Retro.getAppApi().getNextUser(sUserModel.getResponse().getAccess_token(), nextUrl);
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
