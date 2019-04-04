package ceui.lisa.fragments;

import android.support.v7.widget.GridLayoutManager;

import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import io.reactivex.Observable;

public class FragmentIllustList extends BaseListFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank(mUserModel.getResponse().getAccess_token(), "android", "day_male");
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(mContext, 2);
        mRecyclerView.setLayoutManager(gridLayoutManager);
    }
}
