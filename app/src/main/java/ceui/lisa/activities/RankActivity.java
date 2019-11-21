package ceui.lisa.activities;

import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.google.android.material.tabs.TabLayout;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import java.util.Calendar;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentRank;
import ceui.lisa.fragments.FragmentRankNovel;
import ceui.lisa.fragments.NetListFragment;
import ceui.lisa.utils.Common;

public class RankActivity extends BaseActivity implements DatePickerDialog.OnDateSetListener {

    private static final String[] CHINESE_TITLES = new String[]{"日榜", "每周", "每月", "男性向", "女性向", "原创", "新人", "R"};
    private static final String[] CHINESE_TITLES_MANGA = new String[]{"日榜", "每周", "每月", "新人", "R"};
    private static final String[] CHINESE_TITLES_NOVEL = new String[]{"日榜", "每周", "男性向", "女性向", "新人", "R"};
    private ViewPager mViewPager;
    private NetListFragment[] allPages = new NetListFragment[]{null, null, null, null, null, null, null, null};
    private String dataType = "";

    @Override
    protected int initLayout() {
        return R.layout.activity_multi_view_pager;
    }

    @Override
    protected void initView() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        dataType = getIntent().getStringExtra("dataType");
        mViewPager = findViewById(R.id.view_pager);
        String queryDate = getIntent().getStringExtra("date");
        mViewPager.setPageTransformer(true, new DrawerTransformer());

        mViewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                if (allPages[i] == null) {
                    if ("插画".equals(dataType)) {
                        allPages[i] = FragmentRank.newInstance(i, queryDate, false);
                    } else if ("漫画".equals(dataType)) {
                        allPages[i] = FragmentRank.newInstance(i, queryDate, true);
                    } else if ("小说".equals(dataType)) {
                        allPages[i] = FragmentRankNovel.newInstance(i, queryDate);
                    }
                }
                return allPages[i];
            }

            @Override
            public int getCount() {
                if ("插画".equals(dataType)) {
                    return CHINESE_TITLES.length;
                } else if ("漫画".equals(dataType)) {
                    return CHINESE_TITLES_MANGA.length;
                } else if ("小说".equals(dataType)) {
                    return CHINESE_TITLES_NOVEL.length;
                }
                return 0;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                if ("插画".equals(dataType)) {
                    return CHINESE_TITLES[position];
                } else if ("漫画".equals(dataType)) {
                    return CHINESE_TITLES_MANGA[position];
                } else if ("小说".equals(dataType)) {
                    return CHINESE_TITLES_NOVEL[position];
                }
                return "";
            }


        });
        tabLayout.setupWithViewPager(mViewPager);
        //如果指定了跳转到某一个排行，就显示该页排行
        if (getIntent().getIntExtra("index", 0) >= 0) {
            mViewPager.setCurrentItem(getIntent().getIntExtra("index", 0));
        }
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.select_date, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_select_date) {
            Calendar now = Calendar.getInstance();
            now.add(Calendar.DAY_OF_MONTH, -1);
            DatePickerDialog dpd = DatePickerDialog.newInstance(
                    RankActivity.this,
                    now.get(Calendar.YEAR), // Initial year selection
                    now.get(Calendar.MONTH), // Initial month selection
                    now.get(Calendar.DAY_OF_MONTH) // Inital day selection
            );
            Calendar start = Calendar.getInstance();
            start.set(2008, 1, 1);
            dpd.setMinDate(start);
            dpd.setMaxDate(now);
            dpd.setAccentColor(getResources().getColor(R.color.colorPrimary));
            dpd.show(getFragmentManager(), "DatePickerDialog");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDateSet(DatePickerDialog view, int year, int monthOfYear, int dayOfMonth) {
        String date = year + "-" + (monthOfYear + 1) + "-" + (dayOfMonth + 1);
        Common.showLog(date);
        Intent intent = new Intent(mContext, RankActivity.class);
        intent.putExtra("date", date);
        intent.putExtra("dataType", dataType);
        intent.putExtra("index", mViewPager.getCurrentItem());
        startActivity(intent);
        finish();
    }
}
