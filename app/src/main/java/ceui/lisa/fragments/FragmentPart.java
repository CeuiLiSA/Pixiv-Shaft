package ceui.lisa.fragments;

import android.support.v7.widget.LinearLayoutManager;


import ceui.lisa.adapters.PartAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.model.PartResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemDecoration;
import io.reactivex.Observable;

/**
 * 工作项目DEMO
 */
public class FragmentPart extends BaseListFragment<PartResponse, PartAdapter, PartResponse.ElementBean> {

    private String key = "";

    public static FragmentPart newInstance(String word) {
        FragmentPart fragmentPart = new FragmentPart();
        fragmentPart.key = word;
        return fragmentPart;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
    }

    @Override
    String getToolbarTitle() {
        return "搜索配件";
    }

    @Override
    Observable<PartResponse> initApi() {
        return Retro.getPartApi().searchPart(key);
    }

    @Override
    Observable<PartResponse> initNextApi() {
        return null;
    }

    @Override
    void initAdapter() {
        mAdapter = new PartAdapter(allItems, mContext);
    }
}
