package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.UActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.HistoryAdapter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustHistoryEntity;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyViewHistoryBinding;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.DataControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DataChannel;
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
                    DataChannel.get().setIllustList(((HistoryModel)mModel).getAll());
                    Intent intent = new Intent(mContext, ViewPagerActivity.class);
                    intent.putExtra("position", position);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, (int) v.getTag());
                    mContext.startActivity(intent);
                }
            }
        });
    }

    @Override
    public BaseCtrl present() {
        return new DataControl<List<IllustHistoryEntity>>() {
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
    public Class<? extends BaseModel> modelClass() {
        return HistoryModel.class;
    }

    @Override
    public String getToolbarTitle() {
        return "浏览记录";
    }
}
