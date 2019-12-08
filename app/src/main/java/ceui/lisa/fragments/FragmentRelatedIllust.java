package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.core.NetControl;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 相关插画
 */
public class FragmentRelatedIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private int illustID;
    private String mTitle;

    public static FragmentRelatedIllust newInstance(int id, String title) {
        Bundle args = new Bundle();
        args.putInt(Params.ILLUST_ID, id);
        args.putString(Params.ILLUST_TITLE, title);
        FragmentRelatedIllust fragment = new FragmentRelatedIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illustID = bundle.getInt(Params.ILLUST_ID);
        mTitle = bundle.getString(Params.ILLUST_TITLE);
    }

    public void setIllustID(int illustID) {
        this.illustID = illustID;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public NetControl<ListIllustResponse> present() {
        return new NetControl<ListIllustResponse>() {
            @Override
            public Observable<ListIllustResponse> initApi() {
                return Retro.getAppApi().relatedIllust(sUserModel.getResponse().getAccess_token(), illustID);
            }

            @Override
            public Observable<ListIllustResponse> initNextApi() {
                return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return mTitle + "的相关作品";
    }
}
