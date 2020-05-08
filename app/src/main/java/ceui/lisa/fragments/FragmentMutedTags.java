package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.dialogs.AddTagDialog;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;

public class FragmentMutedTags extends LocalListFragment<FragmentBaseListBinding, TagsBean> {

    @Override
    public LocalRepo<List<TagsBean>> repository() {
        return new LocalRepo<List<TagsBean>>() {
            @Override
            public List<TagsBean> first() {
                return TagFilter.getMutedTags();
            }

            @Override
            public List<TagsBean> next() {
                return null;
            }
        };
    }

    @Override
    public BaseAdapter<TagsBean, RecyBookTagBinding> adapter() {
        return new BookedTagAdapter(allItems, mContext, true).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if (viewType == 1) {
                    final TagsBean target = allItems.get(position);
                    PixivOperate.unMuteTag(target);
                    allItems.remove(target);
                    mAdapter.notifyItemRemoved(position);
                    mAdapter.notifyItemRangeChanged(position, allItems.size() - position);
                    if (allItems.size() == 0) {
                        mRecyclerView.setVisibility(View.INVISIBLE);
                        noData.setVisibility(View.VISIBLE);
                    }
                }
            }
        });
    }

    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_delete) {
                    if (allItems.size() == 0) {
                        Common.showToast("当前没有可删除的屏蔽标签");
                    } else {
                        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                        builder.setTitle("PixShaft 提示");
                        builder.setMessage("这将会删除所有的本地屏蔽标签");
                        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                AppDatabase.getAppDatabase(mContext).searchDao().deleteAllMutedTags();
                                Common.showToast("删除成功");
                                mAdapter.clear();
                                noData.setVisibility(View.VISIBLE);
                            }
                        });
                        builder.setNegativeButton("取消", null);
                        AlertDialog alertDialog = builder.create();
                        alertDialog.show();
                    }
                } else if (item.getItemId() == R.id.action_add) {
                    AddTagDialog dialog = AddTagDialog.newInstance(1);
                    dialog.show(getChildFragmentManager(), "AddTagDialog");
                }
                return true;
            }
        });
    }

    public void addMutedTag(String tagName) {
        boolean isExist = false;
        for (TagsBean allItem : allItems) {
            if (allItem.getName().equals(tagName)) {
                isExist = true;
                break;
            }
        }
        if (!isExist) {
            if (allItems.size() == 0) {
                mRecyclerView.setVisibility(View.VISIBLE);
                noData.setVisibility(View.INVISIBLE);
            }
            TagsBean tagsBean = new TagsBean();
            tagsBean.setName(tagName);
            tagsBean.setTranslated_name(tagName);
            PixivOperate.muteTag(tagsBean);
            mModel.getContent().getValue().add(0, tagsBean);
            mAdapter.notifyItemInserted(0);
            mRecyclerView.scrollToPosition(0);
            mAdapter.notifyItemRangeChanged(0, allItems.size());
        } else {
            Common.showToast(tagName + "已存在于屏蔽列表");
        }
    }

    @Override
    public String getToolbarTitle() {
        return "屏蔽记录";
    }
}
