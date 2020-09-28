package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.RelatedIllustRepo;
import ceui.lisa.utils.Params;

/**
 * 相关插画
 */
public class FragmentRelatedIllust extends NetListFragment<FragmentBaseListBinding,
        ListIllust, IllustsBean> {

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

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new RelatedIllustRepo(illustID);
    }

    @Override
    public String getToolbarTitle() {
        return mTitle + getString(R.string.string_231);
    }
}
