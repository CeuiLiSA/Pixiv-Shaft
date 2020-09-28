package ceui.lisa.fragments;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.WalkThroughRepo;

public class FragmentWalkThrough extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new WalkThroughRepo();
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_234);
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
