package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.reflect.TypeToken;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.FileNameAdapter;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentFileNameBinding;
import ceui.lisa.download.FileCreator;
import ceui.lisa.model.CustomFileNameCell;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;

public class FragmentFileName extends SwipeFragment<FragmentFileNameBinding> {

    private IllustsBean illust;
    private List<CustomFileNameCell> allItems = new ArrayList<>();
    private FileNameAdapter mAdapter;

    public static FragmentFileName newInstance() {
        return new FragmentFileName();
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_file_name;
    }

    @Override
    protected void initView() {
        illust = Shaft.sGson.fromJson(Params.EXAMPLE_ILLUST, IllustsBean.class);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.showNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPreview();
            }
        });
        baseBind.saveNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        baseBind.removeAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAdapter != null) {
                    mAdapter.unCheckAll();
                }
            }
        });
        baseBind.lastName.setText(Shaft.sSettings.getFileLastType());
        baseBind.lastName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] items = new String[]{"png", "jpeg", "jpg"};
                int checkedIndex = 0;
                for (int i = 0; i < items.length; i++) {
                    if (items[i].equals(Shaft.sSettings.getFileLastType())) {
                        checkedIndex = i;
                        break;
                    }
                }
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(checkedIndex)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setFileLastType(items[which]);
                                baseBind.lastName.setText(items[which]);
                                Local.setSettings(Shaft.sSettings);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }
        });
    }

    @Override
    protected void initData() {
        allItems.clear();
        if (TextUtils.isEmpty(Shaft.sSettings.getFileNameJson())) {
            allItems.addAll(FileCreator.defaultFileCells());
        } else {
            allItems.addAll(Shaft.sGson.fromJson(Shaft.sSettings.getFileNameJson(),
                    new TypeToken<List<CustomFileNameCell>>() {
                    }.getType()));
        }

        mAdapter = new FileNameAdapter(allItems, mContext);
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.setNestedScrollingEnabled(true);
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
        baseBind.recyclerView.setAdapter(mAdapter);
        new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                return makeMovementFlags(dragFlags, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                Collections.swap(allItems, viewHolder.getAdapterPosition(), target
                        .getAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {

            }

            @Override
            public void onMoved(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, int fromPos, @NonNull RecyclerView.ViewHolder target, int toPos, int x, int y) {
                super.onMoved(recyclerView, viewHolder, fromPos, target, toPos, x, y);
                mAdapter.notifyItemMoved(viewHolder.getAdapterPosition(), target
                        .getAdapterPosition());
            }
        }).attachToRecyclerView(baseBind.recyclerView);

        showPreview();
    }


    private void showPreview() {
        for (CustomFileNameCell allItem : allItems) {
            if (allItem.getCode() == FileCreator.ILLUST_ID && !allItem.isChecked()) {
                Common.showToast("作品ID为必选项，请选择作品ID");
                return;
            }

            if (allItem.getCode() == FileCreator.P_SIZE && !allItem.isChecked()) {
                Common.showToast("作品P数为必选项，请选择作品P数");
                return;
            }
        }
        String name = FileCreator.customFileNameForPreview(illust, allItems, 1);
        baseBind.fileName.setText(name);
    }

    private void saveSettings() {
        for (CustomFileNameCell allItem : allItems) {
            if (allItem.getCode() == FileCreator.ILLUST_ID && !allItem.isChecked()) {
                Common.showToast("作品ID为必选项，请选择作品ID");
                return;
            }

            if (allItem.getCode() == FileCreator.P_SIZE && !allItem.isChecked()) {
                Common.showToast("作品P数为必选项，请选择作品P数");
                return;
            }
        }

        String json = Shaft.sGson.toJson(allItems);
        Shaft.sSettings.setFileNameJson(json);
        Local.setSettings(Shaft.sSettings);
        Common.showToast("保存成功！");
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }
}
