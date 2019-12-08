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

/**
 * 搜索用户
 */
public class FragmentSearchUser extends NetListFragment<FragmentBaseListBinding,
        ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

    private String word;

    public static FragmentSearchUser newInstance(String w) {
        FragmentSearchUser searchUser = new FragmentSearchUser();
        searchUser.word = w;
        return searchUser;
    }

    @Override
    public NetControl<ListUserResponse> present() {
        return new NetControl<ListUserResponse>() {
            @Override
            public Observable<ListUserResponse> initApi() {
                return Retro.getAppApi().searchUser(sUserModel.getResponse().getAccess_token(), word);
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
    public String getToolbarTitle() {
        return "搜索用户 " + word;
    }
}
