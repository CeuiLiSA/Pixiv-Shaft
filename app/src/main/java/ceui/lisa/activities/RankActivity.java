package ceui.lisa.activities;

import android.content.Intent;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import java.time.LocalDate;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityMultiViewPagerBinding;
import ceui.pixiv.ui.rank.RankNovelFeedFragment;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.pixiv.ui.rank.RankIllustFeedFragment;
import ceui.pixiv.utils.ShaftDatePicker;

public class RankActivity extends BaseActivity<ActivityMultiViewPagerBinding> {

    private String dataType = "";
    private String queryDate = "";

    @Override
    protected int initLayout() {
        return R.layout.activity_multi_view_pager;
    }

    @NonNull
    @Override
    protected ActivityMultiViewPagerBinding bindView(@NonNull View root) {
        return ActivityMultiViewPagerBinding.bind(root);
    }

    @Override
    protected void initView() {
        setSupportActionBar(baseBind.toolbar);
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
        baseBind.toolbarTitle.setText(mContext.getString(R.string.ranking_illust));
        dataType = getIntent().getStringExtra("dataType");
        queryDate = getIntent().getStringExtra("date");
//        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());

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

        // 插画/漫画/小说三种榜现在全是 feeds 版，都靠 onResume 懒加载（ViewPager 预创建的相邻 tab
        // 不该偷偷发请求）——必须 RESUME_ONLY_CURRENT，只让可见 tab 到 RESUMED。
        // 小说曾经单独挂 BEHAVIOR_SET_USER_VISIBLE_HINT（那时它还是 legacy BaseLazyFragment），
        // 迁完后若留着那一档，6 个小说榜（含 R18）会在建 pager 时全部发请求。
        final int pagerBehavior = FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT;
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(), pagerBehavior) {
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
        if ("插画".equals(dataType)) {
            mFragments = new Fragment[CHINESE_TITLES.length];
            for (int i = 0; i < CHINESE_TITLES.length; i++) {
                mFragments[i] = RankIllustFeedFragment.newInstance(
                        RankIllustFeedFragment.ILLUST_MODES[i], queryDate);
            }
        } else if ("漫画".equals(dataType)) {
            mFragments = new Fragment[CHINESE_TITLES_MANGA.length];
            for (int i = 0; i < CHINESE_TITLES_MANGA.length; i++) {
                mFragments[i] = RankIllustFeedFragment.newInstance(
                        RankIllustFeedFragment.MANGA_MODES[i], queryDate);
            }
        } else if ("小说".equals(dataType)) {
            mFragments = new Fragment[CHINESE_TITLES_NOVEL.length];
            for (int i = 0; i < CHINESE_TITLES_NOVEL.length; i++) {
                mFragments[i] = RankNovelFeedFragment.newInstance(
                        RankNovelFeedFragment.NOVEL_MODES[i], queryDate);
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
            LocalDate initialDate = null;
            if (!TextUtils.isEmpty(queryDate) && queryDate.contains("-")) {
                try {
                    initialDate = LocalDate.parse(queryDate);
                } catch (Exception ignored) {
                }
            }
            LocalDate yesterday = LocalDate.now().minusDays(1);
            if (initialDate == null) {
                initialDate = yesterday;
            }
            LocalDate minDate = LocalDate.of(2008, 1, 1);

            ShaftDatePicker.show(
                    getSupportFragmentManager(),
                    "DatePickerDialog",
                    null,
                    0,
                    initialDate,
                    minDate,
                    yesterday,
                    pickedDate -> {
                        String date = pickedDate.toString();
                        Common.showLog(date);
                        Intent intent = new Intent(mContext, RankActivity.class);
                        intent.putExtra("date", date);
                        intent.putExtra("dataType", dataType);
                        intent.putExtra("index", baseBind.viewPager.getCurrentItem());
                        startActivity(intent);
                        finish();
                    }
            );
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean hideStatusBar() {
        return false;
    }
}
