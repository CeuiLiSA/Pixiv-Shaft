package ceui.lisa.activities;

import android.content.Intent;
import androidx.annotation.Nullable;

import com.ToxicBakery.viewpager.transforms.CubeOutTransformer;
import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.ToxicBakery.viewpager.transforms.FlipHorizontalTransformer;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;

import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import java.util.Calendar;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentRank;
import ceui.lisa.utils.Common;

public class RankActivity extends BaseActivity implements DatePickerDialog.OnDateSetListener {

    private ViewPager mViewPager;
    private static final String[] CHINESE_TITLES = new String[]{"日榜", "每周", "每月", "男性向", "女性向", "原创", "新人", "R"};
    private FragmentRank[] allPages = new FragmentRank[]{null, null, null, null, null, null, null, null};

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_multi_view_pager;
    }

    @Override
    protected void initView() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        mViewPager = findViewById(R.id.view_pager);
        String queryDate = getIntent().getStringExtra("date");
        mViewPager.setPageTransformer(true, new DrawerTransformer());

        mViewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                if (allPages[i] == null) {
                    allPages[i] = FragmentRank.newInstance(i, queryDate);
                }
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
        tabLayout.setupWithViewPager(mViewPager);
        //如果指定了跳转到某一个排行，就显示该页排行
        if(getIntent().getIntExtra("index", 0) >= 0) {
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
        if(item.getItemId() == R.id.action_select_date){
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
        intent.putExtra("index", mViewPager.getCurrentItem());
        startActivity(intent);
        finish();
    }
}
