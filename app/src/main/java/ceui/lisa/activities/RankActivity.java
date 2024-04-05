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
import ceui.lisa.utils.MyOnTabSelectedListener;

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
        baseBind.toolbarTitle.setText(mContext.getString(R.string.ranking_illust));
        dataType = getIntent().getStringExtra("dataType");
        queryDate = getIntent().getStringExtra("date");
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());

        final String[] CHINESE_TITLES = new String[]{
                mContext.getString(R.string.daily_rank),
                mContext.getString(R.string.weekly_rank),
                mContext.getString(R.string.monthly_rank),
                mContext.getString(R.string.created_by_ai),
                mContext.getString(R.string.man_like),
                mContext.getString(R.string.woman_like),
                mContext.getString(R.string.self_done),
                mContext.getString(R.string.new_fish),
                mContext.getString(R.string.r_eighteen),
                mContext.getString(R.string.r_eighteen_weekly_rank),
                mContext.getString(R.string.r_eighteen_male_rank),
                mContext.getString(R.string.r_eighteen_female_rank),
                mContext.getString(R.string.r_eighteen_ai_rank),
                mContext.getString(R.string.r_eighteen_guro_rank)
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

        final String[] titles = getTitles(CHINESE_TITLES, CHINESE_TITLES_MANGA, CHINESE_TITLES_NOVEL);
        final Fragment[] mFragments = getFragments(CHINESE_TITLES, CHINESE_TITLES_MANGA, CHINESE_TITLES_NOVEL);

        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return mFragments[i];
            }

            @Override
            public int getCount() {
                return titles.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return titles[position];
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        //如果指定了跳转到某一个排行，就显示该页排行
        if (getIntent().getIntExtra("index", 0) >= 0) {
            baseBind.viewPager.setCurrentItem(getIntent().getIntExtra("index", 0));
        }
    }

    private String[] getTitles(String[] CHINESE_TITLES, String[] CHINESE_TITLES_MANGA, String[] CHINESE_TITLES_NOVEL) {
        if ("插画".equals(dataType)) {
            return CHINESE_TITLES;
        } else if ("漫画".equals(dataType)) {
            return CHINESE_TITLES_MANGA;
        } else if ("小说".equals(dataType)) {
            return CHINESE_TITLES_NOVEL;
        }
        return new String[0];
    }

    private Fragment[] getFragments(String[] CHINESE_TITLES, String[] CHINESE_TITLES_MANGA, String[] CHINESE_TITLES_NOVEL) {
        final Fragment[] mFragments;
        if("插画".equals(dataType)){
            mFragments = new Fragment[CHINESE_TITLES.length];
            for (int i = 0; i < CHINESE_TITLES.length; i++) {
                mFragments[i] = FragmentRankIllust.newInstance(i, queryDate, false);
            }
        } else if ("漫画".equals(dataType)) {
            mFragments = new Fragment[CHINESE_TITLES_MANGA.length];
            for (int i = 0; i < CHINESE_TITLES_MANGA.length; i++) {
                mFragments[i] = FragmentRankIllust.newInstance(i, queryDate, true);
            }
        } else if ("小说".equals(dataType)) {
            mFragments = new Fragment[CHINESE_TITLES_NOVEL.length];
            for (int i = 0; i < CHINESE_TITLES_NOVEL.length; i++) {
                mFragments[i] = FragmentRankNovel.newInstance(i, queryDate);
            }
        } else {
            mFragments = new Fragment[0];
        }
        return mFragments;
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
                        Integer.parseInt(t[2]) // Initial day selection
                );
            } else {
                dpd = DatePickerDialog.newInstance(
                        RankActivity.this,
                        now.get(Calendar.YEAR), // Initial year selection
                        now.get(Calendar.MONTH), // Initial month selection
                        now.get(Calendar.DAY_OF_MONTH) // Initial day selection
                );
            }
            Calendar start = Calendar.getInstance();
            start.set(2008, 0, 1);
            dpd.setMinDate(start);
            dpd.setMaxDate(now);
            dpd.setAccentColor(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary));
            dpd.setThemeDark(mContext.getResources().getBoolean(R.bool.is_night_mode));
            dpd.show(getSupportFragmentManager(), "DatePickerDialog");
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
