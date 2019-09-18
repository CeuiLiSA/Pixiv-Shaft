package ceui.lisa.fragments;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.EAdapter;
import ceui.lisa.databinding.RecyUserEventBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentP extends FragmentList<ListIllustResponse, IllustsBean, RecyUserEventBinding> {

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getFollowUserIllust(Shaft.sUserModel.getResponse().getAccess_token());
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initData() {
        super.initData();
        getFirstData();
    }

    @Override
    public void initAdapter() {
        mAdapter = new EAdapter(allItems, mContext);
    }
}
