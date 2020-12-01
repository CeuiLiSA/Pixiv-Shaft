package ceui.lisa.activities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.BarUtils;
import com.mxn.soul.flowingdrawer_core.ElasticDrawer;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.databinding.FragmentNewSearchBinding;
import ceui.lisa.fragments.FragmentFilter;
import ceui.lisa.fragments.FragmentSearchIllust;
import ceui.lisa.fragments.FragmentSearchNovel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.viewmodel.SearchModel;

public class SearchActivity extends BaseActivity<FragmentNewSearchBinding> {

    private final BaseFragment<?>[] allPages = new BaseFragment[]{null, null, null};
    private String keyWord = "";
    private SearchModel searchModel;
    private int index = 0;

    @Override
    protected void initBundle(Bundle bundle) {
        keyWord = bundle.getString(Params.KEY_WORD);
        index = bundle.getInt(Params.INDEX);
        searchModel = new ViewModelProvider(this).get(SearchModel.class);
        searchModel.getKeyword().setValue(keyWord);
        searchModel.getNowGo().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                baseBind.drawerlayout.closeMenu(true);
            }
        });
    }

    @Override
    protected int initLayout() {
        return R.layout.fragment_new_search;
    }

    @Override
    protected void initView() {
        final String[] TITLES = new String[]{
                getString(R.string.string_136),
                getString(R.string.string_137),
                getString(R.string.string_138)
        };
        baseBind.searchBox.setText(keyWord);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(), 0) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (allPages[position] == null) {
                    if (position == 0) {
                        allPages[position] = FragmentSearchIllust.newInstance(false);
                    } else if (position == 1) {
                        allPages[position] = FragmentSearchIllust.newInstance(true);
                    } else {
                        allPages[position] = FragmentSearchNovel.newInstance();
                    }
                }
                return allPages[position];
            }

            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return TITLES[position];
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        if (index != 0) {
            baseBind.viewPager.setCurrentItem(index);
        }
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mActivity.finish();
            }
        });
        baseBind.toolbar.inflateMenu(R.menu.illust_filter);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_filter) {
                    Common.hideKeyboard(mActivity);
                    if (baseBind.drawerlayout.isMenuVisible()) {
                        baseBind.drawerlayout.closeMenu(true);
                    } else {
                        baseBind.drawerlayout.openMenu(true);
                    }
                    return true;
                }
                return false;
            }
        });
        baseBind.searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                searchModel.getKeyword().setValue(baseBind.searchBox.getText().toString());
            }
        });
        baseBind.searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (TextUtils.isEmpty(baseBind.searchBox.getText().toString())) {
                    Common.showToast(getString(R.string.string_139));
                    return false;
                }
                searchModel.getNowGo().setValue("search_now");
                Common.hideKeyboard(mActivity);
                return true;
            }
        });

        baseBind.drawerlayout.setTouchMode(ElasticDrawer.TOUCH_MODE_BEZEL);
        FragmentFilter fragmentFilter = new FragmentFilter();
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (!fragmentFilter.isAdded()) {
            fragmentManager.beginTransaction()
                    .add(R.id.id_container_menu, fragmentFilter)
                    .commit();
        } else {
            fragmentManager.beginTransaction()
                    .show(fragmentFilter)
                    .commit();
        }
    }
}
