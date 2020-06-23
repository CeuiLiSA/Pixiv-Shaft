package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.FilterMapper;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;
import io.reactivex.functions.Function;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人創作的插畫
 */
public class FragmentUserIllust extends NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean> {

    private int userID;
    private boolean showToolbar = false;

    public static FragmentUserIllust newInstance(int userID) {
        return newInstance(userID, false);
    }

    public static FragmentUserIllust newInstance(int userID, boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentUserIllust fragment = new FragmentUserIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getUserSubmitIllust(
                        sUserModel.getResponse().getAccess_token(), userID, "illust");
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(
                        sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }

            @Override
            public Function<ListIllust, ListIllust> mapper() {
                return new FilterMapper();
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        if (showToolbar) {
            return "插画作品";
        } else {
            return super.getToolbarTitle();
        }
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
