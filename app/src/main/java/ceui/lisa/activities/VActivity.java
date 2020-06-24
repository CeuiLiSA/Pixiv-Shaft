package ceui.lisa.activities;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;


import ceui.lisa.R;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.ActivityViewPagerBinding;
import ceui.lisa.fragments.FragmentIllust;
import ceui.lisa.core.Container;
import ceui.lisa.fragments.FragmentSingleIllust2;
import ceui.lisa.utils.Params;

public class VActivity extends BaseActivity<ActivityViewPagerBinding> {

    private String pageUUID = "";
    private int index = 0;

    @Override
    protected void initBundle(Bundle bundle) {
        pageUUID = bundle.getString(Params.PAGE_UUID);
        index = bundle.getInt(Params.POSITION);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_view_pager;
    }

    @Override
    protected void initView() {
        PageData pageData = Container.get().getPage(pageUUID);
        if (pageData != null) {
            final int pageSize = pageData.getIllustList() == null ? 0 : pageData.getIllustList().size();
            baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager(), 0) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
//                    return FragmentIllust.newInstance(pageData.getIllustList().get(position));
                    return FragmentSingleIllust2.newInstance(pageData.getIllustList().get(position));
                }

                @Override
                public int getCount() {
                    return pageSize;
                }
            });
            if (index < pageSize) {
                baseBind.viewPager.setCurrentItem(index);
            }
        }
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
