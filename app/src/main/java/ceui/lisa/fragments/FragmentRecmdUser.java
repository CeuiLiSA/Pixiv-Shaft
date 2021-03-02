package ceui.lisa.fragments;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.RecmdUserRepo;

/**
 * 推荐用户
 */
public class FragmentRecmdUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    @Override
    public RemoteRepo<ListUser> repository() {
        return new RecmdUserRepo(false);
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.recomment_user);
    }
}
