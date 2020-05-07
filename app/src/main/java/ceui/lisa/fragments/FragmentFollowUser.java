package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentFollowUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    private int userID;
    private String starType;
    private boolean showToolbar = false;

    public static FragmentFollowUser newInstance(int userID, String starType, boolean pShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.STAR_TYPE, starType);
        args.putBoolean(Params.FLAG, pShowToolbar);
        FragmentFollowUser fragment = new FragmentFollowUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        starType = bundle.getString(Params.STAR_TYPE);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public RemoteRepo<ListUser> repository() {
        return new RemoteRepo<ListUser>() {
            @Override
            public Observable<ListUser> initApi() {
                return Retro.getAppApi().getFollowUser(token(), userID, starType);
            }

            @Override
            public Observable<ListUser> initNextApi() {
                return Retro.getAppApi().getNextUser(token(), mModel.getNextUrl());
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
