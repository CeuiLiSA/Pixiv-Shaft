package ceui.lisa.activities;

import android.content.Intent;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import java.util.Calendar;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityMultiViewPagerBinding;
import ceui.lisa.fragments.FragmentRankIllust;
import ceui.lisa.fragments.FragmentRankNovel;
import ceui.lisa.utils.Common;

public class RankActivity extends BaseActivity<ActivityMultiViewPagerBinding> implements
        DatePickerDialog.OnDateSetListener {

    private String dataType = "";
    private String queryDate = "";

    @Override
    protected int initLayout() {
        return R.layout.activity_multi_view_pager;
    }

    @Override
    protected void initView() {
        setSupportActionBar(baseBind.toolbar);
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
        baseBind.toolbarTitle.setText("排行榜");
        dataType = getIntent().getStringExtra("dataType");
        queryDate = getIntent().getStringExtra("date");
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());

        final String[] CHINESE_TITLES = new String[]{
                mContext.getString(R.string.daily_rank),
                mContext.getString(R.string.weekly_rank),
                mContext.getString(R.string.monthly_rank),
                mContext.getString(R.string.man_like),
                mContext.getString(R.string.woman_like),
                mContext.getString(R.string.self_done),
                mContext.getString(R.string.new_fish),
                mContext.getString(R.string.r_eighteen),
                mContext.getString(R.string.r_eighteen_weekly_rank),
                mContext.getString(R.string.r_eighteen_male_rank),
                mContext.getString(R.string.r_eighteen_female_rank)
        };

        final String[] CHINESE_TITLES_MANGA = new String[]{
                getString(R.string.string_124),
                getString(R.string.string_125),
                getString(R.string.string_126),
                getString(R.string.string_127),
                getString(R.string.string_128)
        };
        final String[] CHINESE_TITLES_NOVEL = new String[]{
                getString(R.string.string_129),
                getString(R.string.string_130),
                getString(R.string.string_131),
                getString(R.string.string_132),
                getString(R.string.string_133),
                getString(R.string.string_134)
        };

        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                if ("插画".equals(dataType)) {
                    return FragmentRankIllust.newInstance(i, queryDate, false);
                } else if ("漫画".equals(dataType)) {
                    return FragmentRankIllust.newInstance(i, queryDate, true);
                } else if ("小说".equals(dataType)) {
                    return FragmentRankNovel.newInstance(i, queryDate);
                } else {
                    return new Fragment();
                }
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
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        //如果指定了跳转到某一个排行，就显示该页排行
        if (getIntent().getIntExtra("index", 0) >= 0) {
            baseBind.viewPager.setCurrentItem(getIntent().getIntExtra("index", 0));
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
            DatePickerDialog dpd;
            Calendar now = Calendar.getInstance();
            now.add(Calendar.DAY_OF_MONTH, -1);
            if (!TextUtils.isEmpty(queryDate) && queryDate.contains("-")) {
                String[] t = queryDate.split("-");
                dpd = DatePickerDialog.newInstance(
                        RankActivity.this,
                        Integer.parseInt(t[0]), // Initial year selection
                        Integer.parseInt(t[1]) - 1, // Initial month selection
                        Integer.parseInt(t[2]) // Inital day selection
                );
            } else {
                dpd = DatePickerDialog.newInstance(
                        RankActivity.this,
                        now.get(Calendar.YEAR), // Initial year selection
                        now.get(Calendar.MONTH), // Initial month selection
                        now.get(Calendar.DAY_OF_MONTH) // Inital day selection
                );
            }
            Calendar start = Calendar.getInstance();
            start.set(2008, 1, 1);
            dpd.setMinDate(start);
            dpd.setMaxDate(now);
            dpd.setAccentColor(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary));
            dpd.setThemeDark(mContext.getResources().getBoolean(R.bool.is_night_mode));
            dpd.show(getFragmentManager(), "DatePickerDialog");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDateSet(DatePickerDialog view, int year, int monthOfYear, int dayOfMonth) {
        String date = year + "-" + (monthOfYear + 1) + "-" + dayOfMonth;
        Common.showLog(date);
        Intent intent = new Intent(mContext, RankActivity.class);
        intent.putExtra("date", date);
        intent.putExtra("dataType", dataType);
        intent.putExtra("index", baseBind.viewPager.getCurrentItem());
        startActivity(intent);
        finish();
    }

    @Override
    public boolean hideStatusBar() {
        return false;
    }
}
