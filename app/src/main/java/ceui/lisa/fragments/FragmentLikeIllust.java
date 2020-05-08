package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;


import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人收藏的插畫
 */
public class FragmentLikeIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    public static final String TYPE_PUBLUC = "public";
    public static final String TYPE_PRIVATE = "private";
    private int userID;
    private String starType, tag = "";
    private boolean showToolbar = false;

    public static FragmentLikeIllust newInstance(int userID, String starType) {
        return newInstance(userID, starType, false);
    }

    public static FragmentLikeIllust newInstance(int userID, String starType,
                                                 boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.STAR_TYPE, starType);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentLikeIllust fragment = new FragmentLikeIllust();
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
    public RemoteRepo<ListIllust> repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return TextUtils.isEmpty(tag) ?
                        Retro.getAppApi().getUserLikeIllust(sUserModel
                                .getResponse().getAccess_token(), userID, starType) :
                        Retro.getAppApi().getUserLikeIllust(sUserModel
                                .getResponse().getAccess_token(), userID, starType, tag);
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(),
                        mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public String getToolbarTitle() {
        return showToolbar ? "插画/漫画收藏" : super.getToolbarTitle();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if (event.getReceiver().contains(starType)) {
            tag = (String) event.getObject();
            baseBind.refreshLayout.autoRefresh();
        }
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }
}
