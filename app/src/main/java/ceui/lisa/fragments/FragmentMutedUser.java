package ceui.lisa.fragments;

import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.MuteWorksAdapter;
import ceui.lisa.adapters.SimpleUserAdapter;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecySimpleUserBinding;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.helper.IllustFilter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.UserBean;
import ceui.lisa.utils.Common;

public class FragmentMutedUser extends LocalListFragment<FragmentBaseListBinding, UserBean>
        implements Toolbar.OnMenuItemClickListener {

    @Override
    public LocalRepo<List<UserBean>> repository() {
        return new LocalRepo<List<UserBean>>() {
            @Override
            public List<UserBean> first() {
                List<MuteEntity> entityList =
                        AppDatabase.getAppDatabase(Shaft.getContext())
                                .searchDao()
                                .getMutedUser(PAGE_SIZE, 0);
                List<UserBean> userBeanList = new ArrayList<>();
                for (MuteEntity muteEntity : entityList) {
                    UserBean userBean = Shaft.sGson.fromJson(muteEntity.getTagJson(), UserBean.class);
                    userBeanList.add(userBean);
                }
                return userBeanList;
            }

            @Override
            public List<UserBean> next() {
                List<MuteEntity> entityList =
                        AppDatabase.getAppDatabase(Shaft.getContext())
                                .searchDao()
                                .getMutedUser(PAGE_SIZE, allItems.size());
                List<UserBean> userBeanList = new ArrayList<>();
                for (MuteEntity muteEntity : entityList) {
                    UserBean userBean = Shaft.sGson.fromJson(muteEntity.getTagJson(), UserBean.class);
                    userBeanList.add(userBean);
                }
                return userBeanList;
            }

            @Override
            public boolean hasNext() {
                return true;
            }
        };
    }

    @Override
    public BaseAdapter<UserBean, RecySimpleUserBinding> adapter() {
        return new SimpleUserAdapter(allItems, mContext, true);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {
            if (allItems.size() == 0) {
                Common.showToast(getString(R.string.string_388));
            } else {
                new QMUIDialog.MessageDialogBuilder(mActivity)
                        .setTitle(getString(R.string.string_216))
                        .setMessage(getString(R.string.string_389))
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addAction(getString(R.string.string_218), new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                dialog.dismiss();
                            }
                        })
                        .addAction(0, getString(R.string.string_219), QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                AppDatabase.getAppDatabase(mContext).searchDao().deleteAllMutedUsers();
                                Common.showToast(getString(R.string.string_220));
                                mAdapter.clear();
                                emptyRela.setVisibility(View.VISIBLE);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }
        }
        return true;
    }
}
