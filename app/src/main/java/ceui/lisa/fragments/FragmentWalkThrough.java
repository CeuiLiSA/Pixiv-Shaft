package ceui.lisa.fragments;

import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.GridItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentWalkThrough extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                return Retro.getAppApi().getLoginBg(sUserModel.getResponse().getAccess_token() + "123456");
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return "画廊";
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
