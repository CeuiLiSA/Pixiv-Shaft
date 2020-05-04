package ceui.lisa.fragments;

import android.os.Bundle;

import java.util.List;

import ceui.lisa.R;
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

/**
 * 推荐用户
 */
public class FragmentRecmdUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean, RecyUserPreviewBinding> {

    @Override
    public NetControl<ListUser> present() {
        return new NetControl<ListUser>() {
            @Override
            public Observable<ListUser> initApi() {
                return Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListUser> initNextApi() {
                return Retro.getAppApi().getNextUser(
                        sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(mModel.getContent().getValue(), mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.recomment_user);
    }
}
