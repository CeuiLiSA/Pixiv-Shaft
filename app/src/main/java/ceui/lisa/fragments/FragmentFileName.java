package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
                showResult();
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
        allItems.add(new CustomFileNameCell("作品ID", "不选的话可能两个文件名重复，导致下载失败，必选项", 1, true));
        allItems.add(new CustomFileNameCell("作品标题", "作品标题，可选项", 2, false));
        allItems.add(new CustomFileNameCell("画师ID", "画师ID，可选项", 3, false));
        allItems.add(new CustomFileNameCell("画师昵称", "画师昵称，可选项", 4, false));
        allItems.add(new CustomFileNameCell("作品P数", "显示当前图片是作品的第几P，如果只有1P则隐藏，必选项", 5, true));
        allItems.add(new CustomFileNameCell("作品尺寸", "显示当前图片的尺寸信息，可选项", 6, false));
        mAdapter = new FileNameAdapter(allItems, mContext);
        baseBind.recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyclerView.setNestedScrollingEnabled(true);
        baseBind.recyclerView.addItemDecoration(new LinearItemDecoration(DensityUtil.dp2px(12.0f)));
        baseBind.recyclerView.setAdapter(mAdapter);
        new ItemTouchHelper(new ItemTouchHelper.Callback() {
            private RecyclerView.ViewHolder vh;
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
                // 移动完成后刷新列表
                mAdapter.notifyItemMoved(viewHolder.getAdapterPosition(), target
                        .getAdapterPosition());
            }

            //省略代码
        }).attachToRecyclerView(baseBind.recyclerView);
    }


    private void showResult() {
        for (int i = 0; i < allItems.size(); i++) {
            Common.showLog(allItems.get(i).getTitle());
        }
//        if (!baseBind.illustId.isChecked()) {
//            Common.showToast("作品ID为必选项，请选择作品ID");
//            return;
//        }
//
//        if (!baseBind.pSize.isChecked()) {
//            Common.showToast("作品P数为必选项，请选择作品P数");
//            return;
//        }
//
//
//        String illustID = String.valueOf(illust.getId());
//
//        String illustTitle = "";
//        if (baseBind.illustTitle.isChecked()) {
//            illustTitle = illust.getTitle();
//        }
//
//
//        String userId = "";
//        if (baseBind.userId.isChecked()) {
//            userId = String.valueOf(illust.getUser().getId());
//        }
//
//
//        String userName = "";
//        if (baseBind.userName.isChecked()) {
//            userName = illust.getUser().getName();
//        }
//
//        String pSize = "p2";
//
//        String illustSize = "";
//        if (baseBind.illustSize.isChecked()) {
//            illustSize = illust.getWidth() + "px*" + illust.getHeight() + "px";
//        }
//
//
//        String result = "";
//        if (!TextUtils.isEmpty(illustID)) {
//            result = result + illustID;
//        }
//        if (!TextUtils.isEmpty(illustTitle)) {
//            result = result + "_" + illustTitle;
//        }
//        if (!TextUtils.isEmpty(userId)) {
//            result = result + "_" + userId;
//        }
//        if (!TextUtils.isEmpty(userName)) {
//            result = result + "_" + userName;
//        }
//        if (!TextUtils.isEmpty(pSize)) {
//            result = result + "_" + pSize;
//        }
//        if (!TextUtils.isEmpty(illustSize)) {
//            result = result + "_" + illustSize;
//        }
//        result = result + "." + Shaft.sSettings.getFileLastType();
//        baseBind.fileName.setText(result);
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }
}
