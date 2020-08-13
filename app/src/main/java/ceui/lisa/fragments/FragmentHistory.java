package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.util.List;
import java.util.UUID;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UserActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.HistoryAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.core.PageData;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.core.Container;
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
                    final String uuid = UUID.randomUUID().toString();
                    final PageData pageData = new PageData(uuid, ((HistoryModel)mModel).getAll());
                    Container.get().addPageToMap(pageData);

                    Intent intent = new Intent(mContext, VActivity.class);
                    intent.putExtra(Params.POSITION, position);
                    intent.putExtra(Params.PAGE_UUID, uuid);
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
                    if (allItems.size() == 0) {
                        Common.showToast("没有浏览历史");
                    } else {
                        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                        builder.setTitle("PixShaft 提示");
                        builder.setMessage("这将会删除所有的本地浏览历史");
                        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllHistory();
                                Common.showToast("删除成功");
                                mRefreshLayout.autoRefresh();
                            }
                        });
                        builder.setNegativeButton("取消", null);
                        AlertDialog alertDialog = builder.create();
                        alertDialog.show();
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
