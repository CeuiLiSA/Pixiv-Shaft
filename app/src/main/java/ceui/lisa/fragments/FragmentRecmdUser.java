package ceui.lisa.fragments;

import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.feature.worker.BatchFollowTask;
import ceui.lisa.feature.worker.Worker;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.RecmdUserRepo;

/**
 * 推荐用户
 */
public class FragmentRecmdUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

//    @Override
//    public void initView() {
//        super.initView();
//        baseBind.toolbar.inflateMenu(R.menu.batch_do);
//        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
//            @Override
//            public boolean onMenuItemClick(MenuItem item) {
//                if (item.getItemId() == R.id.action_add) {
//                    for (UserPreviewsBean allItem : allItems) {
//                        BatchFollowTask task = new BatchFollowTask(allItem.getUser().getName(),
//                                allItem.getUser().getUserId(), 0);
//                        Worker.get().addTask(task);
//                    }
//                    Worker.get().start();
//                }
//                return false;
//            }
//        });
//    }

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
