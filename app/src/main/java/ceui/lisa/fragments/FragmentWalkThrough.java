package ceui.lisa.fragments;

import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.GridItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentWalkThrough extends NetListFragment<FragmentBaseListBinding,
        ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    @Override
    public NetControl<ListIllustResponse> present() {
        return new NetControl<ListIllustResponse>() {
            @Override
            public Observable<ListIllustResponse> initApi() {
                return Retro.getAppApi().getLoginBg(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListIllustResponse> initNextApi() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext, true);
    }

    @Override
    public String getToolbarTitle() {
        return "画廊";
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 2);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new GridItemDecoration(2,
                DensityUtil.dp2px(8.0f), true));
    }
}
