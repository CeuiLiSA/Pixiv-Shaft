package ceui.lisa.activities;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;


import ceui.lisa.R;
import ceui.lisa.core.Container;
import ceui.lisa.core.IDWithList;
import ceui.lisa.core.TimeRecord;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.ActivityViewPagerBinding;
import ceui.lisa.fragments.FragmentIllust;
import ceui.lisa.fragments.FragmentSingleIllust2;
import ceui.lisa.models.IllustsBean;
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
        IDWithList<IllustsBean> idWithList = Container.get().getPage(pageUUID);
        if (idWithList != null) {
            final int pageSize = idWithList.getList() == null ? 0 : idWithList.getList().size();
            baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager(), 0) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
//                    return FragmentIllust.newInstance(idWithList.getList().get(position));
                    return FragmentSingleIllust2.newInstance(idWithList.getList().get(position));
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

        TimeRecord.end();
        TimeRecord.result();
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
