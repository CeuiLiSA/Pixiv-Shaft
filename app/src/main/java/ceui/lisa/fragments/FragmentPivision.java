package ceui.lisa.fragments;

import android.support.v7.widget.LinearLayoutManager;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.adapters.ArticalAdapter;
import ceui.lisa.network.Retro;
import ceui.lisa.response.ArticalResponse;
import ceui.lisa.utils.LinearItemDecoration;
import io.reactivex.Observable;

public class FragmentPivision extends BaseListFragment<ArticalResponse, ArticalAdapter, ArticalResponse.SpotlightArticlesBean> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    Observable<ArticalResponse> initApi() {
        return Retro.getAppApi().getArticals(mUserModel.getResponse().getAccess_token());
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(16.0f)));
        mRecyclerView.setHasFixedSize(true);
    }

    @Override
    Observable<ArticalResponse> initNextApi() {
        return Retro.getAppApi().getNextArticals(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new ArticalAdapter(allItems, mContext);
    }
}
