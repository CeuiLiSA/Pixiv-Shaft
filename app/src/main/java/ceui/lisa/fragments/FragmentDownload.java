package ceui.lisa.fragments;

import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.Manager;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.MyOnTabSelectedListener;

/**
 * 下载管理
 */
public class FragmentDownload extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private final Fragment[] allPages = new Fragment[]{new FragmentDownloading(), new FragmentDownloadFinish()};

    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        String[] CHINESE_TITLES = new String[]{
                Shaft.getContext().getString(R.string.now_downloading),
                Shaft.getContext().getString(R.string.has_download)
        };
        baseBind.toolbarTitle.setText(R.string.string_203);
        baseBind.toolbar.inflateMenu(R.menu.start_all);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_delete) {
                    if (allPages[1] instanceof FragmentDownloadFinish &&
                            ((FragmentDownloadFinish) allPages[1]).getCount() > 0) {
                        new QMUIDialog.MessageDialogBuilder(mActivity)
                                .setTitle("提示")
                                .setMessage("这将会删除所有的下载记录，但是已下载的文件不会被删除")
                                .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                                .addAction("取消", new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        dialog.dismiss();
                                    }
                                })
                                .addAction(0, "删除", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllDownload();
                                        ((FragmentDownloadFinish) allPages[1]).clearAndRefresh();
                                        Common.showToast("下载记录清除成功");
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    } else {
                        Common.showToast("没有可删除的记录");
                    }
                    return true;
                } else if (item.getItemId() == R.id.action_start) {
                    Manager.get().startAll();
                    if (allPages[0] instanceof FragmentDownloading){
                        final BaseAdapter<?, ?> adapter = ((FragmentDownloading) allPages[0]).mAdapter;
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                } else if (item.getItemId() == R.id.action_stop) {
                    Manager.get().stopAll();
                    if (allPages[0] instanceof FragmentDownloading){
                        final BaseAdapter<?, ?> adapter = ((FragmentDownloading) allPages[0]).mAdapter;
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                } else if (item.getItemId() == R.id.action_clear) {
                    if (allPages[0] instanceof FragmentDownloading &&
                            ((FragmentDownloading) allPages[0]).getCount() > 0) {
                        new QMUIDialog.MessageDialogBuilder(mActivity)
                                .setTitle("提示")
                                .setMessage("清空所有未完成的任务吗？")
                                .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                                .addAction("取消", new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        dialog.dismiss();
                                    }
                                })
                                .addAction(0, "清空", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        Manager.get().clearAll();
                                        ((FragmentDownloading) allPages[0]).clearAndRefresh();
                                        Common.showToast("下载任务清除成功");
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    } else {
                        Common.showToast("没有可删除的记录");
                    }
                }
                return false;
            }
        });
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return allPages[i];
            }

            @Override
            public int getCount() {
                return CHINESE_TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return CHINESE_TITLES[position];
            }


        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(allPages);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                if (i == 0) {
                    baseBind.toolbar.getMenu().clear();
                    baseBind.toolbar.inflateMenu(R.menu.start_all);
                } else {
                    baseBind.toolbar.getMenu().clear();
                    baseBind.toolbar.inflateMenu(R.menu.delete_all);
                }
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }
}
