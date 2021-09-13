package ceui.lisa.fragments;

import java.util.List;

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

    // Bad implementation, should use view model to share repo between fragments
    private List<UserPreviewsBean> outerItems;
    private String outerNextUrl;

    public FragmentRecmdUser() {

    }

    public FragmentRecmdUser(List<UserPreviewsBean> items, String url) {
        outerItems = items;
        outerNextUrl = url;
    }

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

    @Override
    public boolean autoRefresh() {
        return !(allItems != null && allItems.size() > 0);
    }

    @Override
    public void lazyData() {
        if (allItems.size() == 0 && outerItems != null && outerItems.size() > 0) {
            allItems.addAll(outerItems);
            mRemoteRepo.setNextUrl(outerNextUrl);
        }
        super.lazyData();
    }
}
