package ceui.lisa.activities;

import android.content.DialogInterface;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentDownloadFinish;
import ceui.lisa.fragments.FragmentNowDownload;
import ceui.lisa.utils.Common;

public class DownloadManageActivity extends BaseActivity<ViewpagerWithTablayoutBinding> {

    private static final String[] CHINESE_TITLES = new String[]{
            Shaft.getContext().getString(R.string.now_downloading),
            Shaft.getContext().getString(R.string.has_download)
    };
    private BaseFragment[] allPages = new BaseFragment[]{new FragmentNowDownload(), new FragmentDownloadFinish()};

    @Override
    protected int initLayout() {
        return R.layout.viewpager_with_tablayout;
    }

    @Override
    protected void initView() {
        setSupportActionBar(baseBind.toolbar);
        baseBind.toolbar.setTitle("下载管理");
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());
        baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager()) {
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
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                invalidateOptionsMenu();
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (baseBind.viewPager != null) {
            if (baseBind.viewPager.getCurrentItem() == 0) {
                return false;
            } else {
                getMenuInflater().inflate(R.menu.delete_all, menu);
                return true;
            }
        } else {
            return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete) {

            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setTitle("Shaft 提示");
            builder.setMessage("这将会删除所有的下载记录，但是已下载的文件不会被删除");
            builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllDownload();
                    Common.showToast("下载记录清除成功");
                    if (allPages[1] instanceof FragmentDownloadFinish) {
                        ((FragmentDownloadFinish) allPages[1]).getFirstData();
                    }
                }
            });
            builder.setNegativeButton("取消", null);
            AlertDialog alertDialog = builder.create();
            alertDialog.show();
        }
        return super.onOptionsItemSelected(item);
    }
}
