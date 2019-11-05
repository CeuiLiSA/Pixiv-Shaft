package ceui.lisa.ui.fragment;

import android.content.Context;

import com.scwang.smartrefresh.header.DropBoxHeader;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.databinding.FragmentQBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.fragments.NetListFragment;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.model.ListUserResponse;
import ceui.lisa.model.UserPreviewsBean;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentQ extends NetListFragment<FragmentQBinding, ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

    @Override
    public NetControl<ListUserResponse> present() {
        return new NetControl<ListUserResponse>() {
            @Override
            public Observable<ListUserResponse> initApi() {
                return Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListUserResponse> initNextApi() {
                return Retro.getAppApi().getNextUser(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        mAdapter = new UAdapter(allItems, mContext);
        return mAdapter;
    }
}
