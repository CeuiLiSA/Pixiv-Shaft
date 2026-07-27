package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Params;
import ceui.pixiv.feeds.FeedFragment;
import ceui.pixiv.ui.muted.MutedObjectsFeedFragment;
import ceui.pixiv.ui.muted.MutedTagsFeedFragment;
import ceui.pixiv.ui.muted.MutedUserFeedFragment;
import ceui.pixiv.ui.rank.RankIllustFeedFragment;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class FragmentViewPager extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private static final int REQUEST_CODE_IMPORT_MUTE = 20082;
    private static final String MUTE_RECORDS_FILE_NAME = "Shaft-MuteRecords.json";

    private String title;
    /**
     * 两个分支现在都是 feeds 的 {@link FeedFragment}（屏蔽记录三 tab 已迁 feeds、R18 榜本就是），
     * 故按共同基类 {@link Fragment} 存。
     */
    private Fragment[] mFragments = null;

    public static FragmentViewPager newInstance(String title) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        FragmentViewPager fragment = new FragmentViewPager();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initBundle(Bundle bundle) {
        title = bundle.getString(Params.TITLE);
    }


    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        if (TextUtils.equals(title, Params.VIEW_PAGER_MUTED)) {
            String[] CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.string_353),
                    Shaft.getContext().getString(R.string.string_381),
                    Shaft.getContext().getString(R.string.string_354),
            };
            mFragments = new Fragment[]{
                    new MutedTagsFeedFragment(),
                    new MutedUserFeedFragment(),
                    new MutedObjectsFeedFragment(),
            };
            baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
            setMuteMenuListener(mFragments[0]);
            baseBind.toolbarTitle.setText(R.string.muted_history);
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return mFragments[position];
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
            baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                }

                @Override
                public void onPageSelected(int position) {
                    setMuteMenuListener(mFragments[position]);
                    if (position == 0) {
                        baseBind.toolbar.getMenu().clear();
                        baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
                    } else {
                        baseBind.toolbar.getMenu().clear();
                        baseBind.toolbar.inflateMenu(R.menu.delete_muted_history);
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {

                }
            });

        } else if (TextUtils.equals(title, Params.VIEW_PAGER_R18)) {
            baseBind.toolbar.setVisibility(View.GONE);
            String[] CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.r_eighteen),
                    Shaft.getContext().getString(R.string.r_eighteen_weekly_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_male_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_female_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_ai_rank)
            };
            // mode 写字面量而不借 RankIllustFeedFragment.ILLUST_MODES 的下标：那个数组的顺序
            // 是 RankActivity 的 tab 顺序，借下标等于把这页绑在它的排版上（改序即静默换榜）。
            // date 传 null = 当前榜单（走首屏磁盘缓存），与 legacy 传空串等价。
            mFragments = new Fragment[]{
                    RankIllustFeedFragment.newInstance("day_r18", null),
                    RankIllustFeedFragment.newInstance("week_r18", null),
                    RankIllustFeedFragment.newInstance("day_male_r18", null),
                    RankIllustFeedFragment.newInstance("day_female_r18", null),
                    RankIllustFeedFragment.newInstance("day_r18_ai", null),
            };
            baseBind.toolbarTitle.setText(R.string.string_r);
            // feeds 版靠 onResume 懒加载，必须 RESUME_ONLY_CURRENT：默认那档下 ViewPager 预创建的
            // 相邻 tab 也会到 RESUMED，没打开过的榜会跟着发请求（offscreenPageLimit=1，所以是开页
            // 即多发一个、往后每划一格再多一个）。legacy FragmentRankIllust 走 BaseLazyFragment
            // .setUserVisibleHint 懒加载，靠的正是这里原本的默认档，两套机制别混用。
            // 注意「懒」只对非当前 tab 成立：宿主 MainActivity 的 pager 设了
            // offscreenPageLimit(length-1)，本页在冷启就 RESUMED，首个榜(day_r18)照发请求——
            // 这点 legacy 也一样，不是本次改动引入的。
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(),
                    FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return mFragments[position];
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

        }
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
    }

    public void forceRefresh() {
        try {
            Fragment current = mFragments[baseBind.viewPager.getCurrentItem()];
            if (current instanceof FeedFragment) {
                ((FeedFragment) current).forceRefresh();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 导入屏蔽记录后刷新全部 tab（而非仅当前 tab）：三个屏蔽 tab 现在是 eager 的 FeedFragment，
     * 开页即各自加载过一次、之后不再自查；只刷当前 tab 会让导入到别的 tab 的记录一直不显示，
     * 直到关掉重开。legacy 三 tab 是懒加载（BaseLazyFragment），首次可见才拉，无此问题。
     * 尚未创建视图的 tab（超出 offscreenPageLimit）forceRefresh 会 no-op，由框架在首次可见时
     * 自然拉到最新数据。
     */
    private void refreshAllMutedTabs() {
        if (mFragments == null) {
            return;
        }
        for (Fragment fragment : mFragments) {
            if (fragment instanceof FeedFragment) {
                ((FeedFragment) fragment).forceRefresh();
            }
        }
    }

    /**
     * 仅屏蔽记录分支用：那三个 tab（MutedTagsFeedFragment / MutedUserFeedFragment /
     * MutedObjectsFeedFragment）各自 implements OnMenuItemClickListener——是子类实现的，基类
     * FeedFragment 并没有，所以这里只能运行时转型。R18 分支的 feeds fragment 不走这条路
     *（它没有 toolbar 菜单）。
     */
    private void setMuteMenuListener(Fragment delegate) {
        baseBind.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_export_mute) {
                exportMuteRecords();
                return true;
            } else if (item.getItemId() == R.id.action_import_mute) {
                pickMuteRecordsFile();
                return true;
            }
            return ((Toolbar.OnMenuItemClickListener) delegate).onMenuItemClick(item);
        });
    }

    private void exportMuteRecords() {
        IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity,
                MUTE_RECORDS_FILE_NAME, new Callback<File>() {
                    @Override
                    public void doSomething(File file) {
                        List<MuteEntity> all = AppDatabase.getAppDatabase(mContext)
                                .searchDao().getAllMuteEntities();
                        if (all == null || all.isEmpty()) {
                            Common.showToast(getString(R.string.mute_records_export_empty));
                            return;
                        }
                        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
                            writer.beginArray();
                            for (MuteEntity entity : all) {
                                Shaft.sGson.toJson(entity, MuteEntity.class, writer);
                            }
                            writer.endArray();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        Common.showToast(getString(R.string.mute_records_export_success, all.size()));
                    }
                }, null);
    }

    private void pickMuteRecordsFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:"
                    + "Download%2fShaftBackups%2f" + MUTE_RECORDS_FILE_NAME);
            intent.putExtra(EXTRA_INITIAL_URI, initialUri);
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT_MUTE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_MUTE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                Common.showToast(getString(R.string.mute_records_import_no_file));
                return;
            }
            new Thread(() -> {
                try {
                    InputStream is = mContext.getContentResolver().openInputStream(uri);
                    if (is == null) {
                        mActivity.runOnUiThread(() ->
                                Common.showToast(getString(R.string.mute_records_import_no_file)));
                        return;
                    }
                    int imported = 0;
                    try (JsonReader reader = new JsonReader(new InputStreamReader(is))) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            MuteEntity entity = Shaft.sGson.fromJson(reader, MuteEntity.class);
                            if (entity == null || entity.getTagJson() == null || entity.getTagJson().isEmpty()) {
                                continue;
                            }
                            AppDatabase.getAppDatabase(mContext).searchDao().insertMuteTag(entity);
                            imported++;
                        }
                        reader.endArray();
                    }
                    if (imported == 0) {
                        mActivity.runOnUiThread(() ->
                                Common.showToast(getString(R.string.mute_records_import_invalid)));
                        return;
                    }
                    int finalImported = imported;
                    mActivity.runOnUiThread(() -> {
                        refreshAllMutedTabs();
                        Common.showToast(getString(R.string.mute_records_import_success, finalImported));
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    mActivity.runOnUiThread(() ->
                            Common.showToast(getString(R.string.mute_records_import_failed, String.valueOf(e.getMessage()))));
                }
            }).start();
        }
    }
}
