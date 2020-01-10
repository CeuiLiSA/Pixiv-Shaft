package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

public class FragmentWhoFollowThisUser extends NetListFragment<FragmentBaseListBinding,
        ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

    private int userID;

    public static FragmentWhoFollowThisUser newInstance(int userId) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userId);
        FragmentWhoFollowThisUser fragment = new FragmentWhoFollowThisUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
    }

    @Override
    public NetControl<ListUserResponse> present() {
        return new NetControl<ListUserResponse>() {
            @Override
            public Observable<ListUserResponse> initApi() {
                return Retro.getAppApi().getWhoFollowThisUser(Shaft.sUserModel.getResponse().getAccess_token(), userID);
            }

            @Override
            public Observable<ListUserResponse> initNextApi() {
                return Retro.getAppApi().getNextUser(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }
}
