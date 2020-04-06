package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.view.MenuItem;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.core.DataControl;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.Common;

public class FragmentMutedTags extends LocalListFragment<FragmentBaseListBinding, TagsBean, RecyBookTagBinding> {

    @Override
    public DataControl<List<TagsBean>> present() {
        return new DataControl<List<TagsBean>>() {
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
        return new BookedTagAdapter(allItems, mContext, true);
    }

    @Override
    public void initToolbar(Toolbar toolbar) {
        super.initToolbar(toolbar);
        baseBind.toolbar.inflateMenu(R.menu.delete_all);
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
    public String getToolbarTitle() {
        return "屏蔽记录";
    }
}
