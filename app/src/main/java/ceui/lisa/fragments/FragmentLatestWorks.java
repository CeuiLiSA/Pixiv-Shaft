package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.LatestIllustRepo;
import ceui.lisa.utils.Params;

public class FragmentLatestWorks extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean> {

    private String workType;

    public static FragmentLatestWorks newInstance(String paramWorkType) {
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, paramWorkType);
        FragmentLatestWorks fragment = new FragmentLatestWorks();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        workType = bundle.getString(Params.DATA_TYPE);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new LatestIllustRepo(workType);
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
