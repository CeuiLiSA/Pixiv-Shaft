package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.HistoryAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.Container;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.core.PageData;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.viewmodel.BaseModel;
import ceui.lisa.viewmodel.HistoryModel;


public class FragmentHistory extends LocalListFragment<FragmentBaseListBinding,
        IllustHistoryEntity> {

    @Override
    public BaseAdapter<IllustHistoryEntity, RecyViewHistoryBinding> adapter() {
        return new HistoryAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Common.showLog(className + position + " " + allItems.size());
                if (viewType == 0) {
                    final PageData pageData = new PageData(((HistoryModel)mModel).getAll());
                    Container.get().addPageToMap(pageData);

                    Intent intent = new Intent(mContext, VActivity.class);
                    intent.putExtra(Params.POSITION, position);
                    intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    Intent intent = new Intent(mContext, UserActivity.class);
                    intent.putExtra(Params.USER_ID, (int) v.getTag());
                    mContext.startActivity(intent);
                }
            }
        });
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<IllustHistoryEntity>>() {
            @Override
            public List<IllustHistoryEntity> first() {
                return AppDatabase.getAppDatabase(mContext)
                        .downloadDao().getAllViewHistory(PAGE_SIZE, 0);
            }

            @Override
            public List<IllustHistoryEntity> next() {
                return AppDatabase.getAppDatabase(mContext)
                        .downloadDao().getAllViewHistory(PAGE_SIZE, allItems.size());
            }

            @Override
            public boolean hasNext() {
                return true;
            }
        };
    }

    @Override
    public void onFirstLoaded(List<IllustHistoryEntity> illustHistoryEntities) {
        ((HistoryModel)mModel).getAll().clear();
        for (int i = 0; i < illustHistoryEntities.size(); i++) {
            if (illustHistoryEntities.get(i).getType() == 0) {
                IllustsBean illustsBean = Shaft.sGson.fromJson(
                        illustHistoryEntities.get(i).getIllustJson(), IllustsBean.class);
                ((HistoryModel)mModel).getAll().add(illustsBean);
            }
        }
    }

    @Override
    public void onNextLoaded(List<IllustHistoryEntity> illustHistoryEntities) {
        for (int i = 0; i < illustHistoryEntities.size(); i++) {
            if (illustHistoryEntities.get(i).getType() == 0) {
                IllustsBean illustsBean = Shaft.sGson.fromJson(
                        illustHistoryEntities.get(i).getIllustJson(), IllustsBean.class);
                ((HistoryModel)mModel).getAll().add(illustsBean);
            }
        }
    }

    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        toolbar.inflateMenu(R.menu.delete_all);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_delete) {
                    if (Common.isEmpty(allItems)) {
                        Common.showToast(getString(R.string.string_254));
                    } else {
                        new QMUIDialog.MessageDialogBuilder(mActivity)
                                .setTitle(getString(R.string.string_143))
                                .setMessage(getString(R.string.string_255))
                                .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                                .addAction(getString(R.string.string_142), new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        dialog.dismiss();
                                    }
                                })
                                .addAction(0, getString(R.string.string_141), QMUIDialogAction.ACTION_PROP_NEGATIVE,
                                        new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllHistory();
                                        Common.showToast(getString(R.string.string_220));
                                        dialog.dismiss();
                                        mAdapter.clear();
                                        emptyRela.setVisibility(View.VISIBLE);
                                    }
                                })
                                .show();
                    }
                }
                return true;
            }
        });
    }

    @Override
    public Class<? extends BaseModel<?>> modelClass() {
        return HistoryModel.class;
    }

    @Override
    public String getToolbarTitle() {
        return "浏览记录";
    }
}
