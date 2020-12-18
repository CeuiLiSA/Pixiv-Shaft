package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.reflect.TypeToken;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.FileNameAdapter;
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
        baseBind.toolbar.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.toolbar.setTitle(R.string.string_242);
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
        baseBind.hasP0.setChecked(Shaft.sSettings.isHasP0());
        baseBind.hasP0.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setHasP0(isChecked);
                Common.showToast("设置成功");
                Local.setSettings(Shaft.sSettings);
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
