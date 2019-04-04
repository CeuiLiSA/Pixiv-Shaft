package ceui.lisa.fragments;

import android.support.v7.widget.GridLayoutManager;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.GridItemDecoration;
import io.reactivex.Observable;

public class FragmentIllustList extends BaseListFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank("Bearer " + mUserModel.getResponse().getAccess_token(), "for_android", "day_male");
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(mContext, 2);
        mRecyclerView.setLayoutManager(gridLayoutManager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(8.0f), true));
    }
}
