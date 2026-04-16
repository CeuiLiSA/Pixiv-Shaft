package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.UriUtils;
import com.google.gson.reflect.TypeToken;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import java.lang.reflect.Type;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.core.Manager;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.MyOnTabSelectedListener;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

/**
 * 下载管理
 */
public class FragmentDownload extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private static final int REQUEST_CODE_IMPORT_DOWNLOADS = 20081;
    private static final String DOWNLOAD_RECORDS_FILE_NAME = "Shaft-Downloads.json";

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
        baseBind.placeHolder.setVisibility(View.VISIBLE);
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) baseBind.placeHolder.getLayoutParams();
        p.height = BarUtils.getStatusBarHeight();
        baseBind.placeHolder.setLayoutParams(p);
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
                } else if (item.getItemId() == R.id.action_export_downloads) {
                    exportDownloadRecords();
                    return true;
                } else if (item.getItemId() == R.id.action_import_downloads) {
                    pickDownloadRecordsFile();
                    return true;
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

    private void exportDownloadRecords() {
        List<DownloadEntity> all = AppDatabase.getAppDatabase(mContext)
                .downloadDao().getAll(Integer.MAX_VALUE, 0);
        if (all == null || all.isEmpty()) {
            Common.showToast(getString(R.string.download_records_export_empty));
            return;
        }
        String json = Shaft.sGson.toJson(all);
        IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity,
                DOWNLOAD_RECORDS_FILE_NAME, json, new Callback<Uri>() {
                    @Override
                    public void doSomething(Uri t) {
                        Common.showToast(getString(R.string.download_records_export_success, all.size()));
                    }
                });
    }

    private void pickDownloadRecordsFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:"
                    + "Download%2fShaftBackups%2f" + DOWNLOAD_RECORDS_FILE_NAME);
            intent.putExtra(EXTRA_INITIAL_URI, initialUri);
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT_DOWNLOADS);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_DOWNLOADS && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                if (uri == null) {
                    Common.showToast(getString(R.string.download_records_import_no_file));
                    return;
                }
                String fileString = new String(UriUtils.uri2Bytes(uri));
                Type listType = new TypeToken<List<DownloadEntity>>() {}.getType();
                List<DownloadEntity> entities = Shaft.sGson.fromJson(fileString, listType);
                if (entities == null || entities.isEmpty()) {
                    Common.showToast(getString(R.string.download_records_import_invalid));
                    return;
                }
                DownloadDao dao = AppDatabase.getAppDatabase(mContext).downloadDao();
                int imported = 0;
                for (DownloadEntity entity : entities) {
                    if (entity == null || entity.getFileName() == null || entity.getFileName().isEmpty()) {
                        continue;
                    }
                    dao.insert(entity);
                    imported++;
                }
                if (allPages[1] instanceof FragmentDownloadFinish) {
                    ((FragmentDownloadFinish) allPages[1]).clearAndRefresh();
                }
                Common.showToast(getString(R.string.download_records_import_success, imported));
            } catch (Exception e) {
                e.printStackTrace();
                Common.showToast(getString(R.string.download_records_import_failed, String.valueOf(e.getMessage())));
            }
        }
    }
}
