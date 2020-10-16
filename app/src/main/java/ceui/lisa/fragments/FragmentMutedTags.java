package ceui.lisa.fragments;

import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.BookedTagAdapter;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyBookTagBinding;
import ceui.lisa.helper.TagFilter;
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
                        emptyRela.setVisibility(View.VISIBLE);
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
                        Common.showToast(getString(R.string.string_215));
                    } else {
                        new QMUIDialog.MessageDialogBuilder(mActivity)
                                .setTitle(getString(R.string.string_216))
                                .setMessage(getString(R.string.string_217))
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
                                        AppDatabase.getAppDatabase(mContext).searchDao().deleteAllMutedTags();
                                        Common.showToast(getString(R.string.string_220));
                                        mAdapter.clear();
                                        emptyRela.setVisibility(View.VISIBLE);
                                        dialog.dismiss();
                                    }
                                })
                                .create()
                                .show();
                    }
                } else if (item.getItemId() == R.id.action_add) {
                    final QMUIDialog.EditTextDialogBuilder builder = new QMUIDialog.EditTextDialogBuilder(mActivity);
                    builder.setTitle(getString(R.string.string_210))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .setPlaceholder(getString(R.string.string_211))
                            .setInputType(InputType.TYPE_CLASS_TEXT)
                            .addAction(getString(R.string.string_212), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(getString(R.string.string_213), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    CharSequence text = builder.getEditText().getText();
                                    if (text != null && text.length() > 0) {
                                        addMutedTag(text.toString());
                                        dialog.dismiss();
                                    } else {
                                        Toast.makeText(getActivity(), R.string.string_214, Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .show();
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
                emptyRela.setVisibility(View.INVISIBLE);
            }

            TagsBean tagsBean = new TagsBean();
            tagsBean.setName(tagName);
            PixivOperate.muteTag(tagsBean);
            allItems.add(0, tagsBean);
            mAdapter.notifyItemInserted(0);
            mRecyclerView.scrollToPosition(0);
            mAdapter.notifyItemRangeChanged(0, allItems.size());
        } else {
            Common.showToast(tagName + getString(R.string.string_209));
        }
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.muted_history);
    }
}
