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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;

public class FragmentViewPager extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private static final int REQUEST_CODE_IMPORT_MUTE = 20082;
    private static final String MUTE_RECORDS_FILE_NAME = "Shaft-MuteRecords.json";

    private String title;
    private ListFragment[] mFragments = null;

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
        // EdgeToEdge: push AppBarLayout below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.appBar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, 0);
            return windowInsets;
        });

        if (TextUtils.equals(title, Params.VIEW_PAGER_MUTED)) {
            String[] CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.string_353),
                    Shaft.getContext().getString(R.string.string_381),
                    Shaft.getContext().getString(R.string.string_354),
            };
            mFragments = new ListFragment[]{
                    new FragmentMutedTags(),
                    new FragmentMutedUser(),
                    new FragmentMutedObjects(),
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
            mFragments = new ListFragment[]{
//                    FragmentRankIllust.newInstance(7, "", false),
                    FragmentRankIllust.newInstance(8, "", false),
                    FragmentRankIllust.newInstance(9, "", false),
                    FragmentRankIllust.newInstance(10, "", false),
                    FragmentRankIllust.newInstance(11, "", false),
                    FragmentRankIllust.newInstance(12, "", false)
            };
            baseBind.toolbarTitle.setText(R.string.string_r);
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

        }
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
    }

    public void forceRefresh() {
        try {
            mFragments[baseBind.viewPager.getCurrentItem()].forceRefresh();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setMuteMenuListener(ListFragment delegate) {
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
                        forceRefresh();
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
