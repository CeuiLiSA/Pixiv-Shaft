package ceui.lisa.utils;

import com.google.android.material.tabs.TabLayout;

import androidx.fragment.app.Fragment;
import ceui.lisa.fragments.ListFragment;

public class MyOnTabSelectedListener implements TabLayout.OnTabSelectedListener {

    private final Fragment[] fragments;

    public MyOnTabSelectedListener(Fragment[] fragments) {
        this.fragments = fragments;
    }

    @Override
    public void onTabSelected(TabLayout.Tab tab) {

    }

    @Override
    public void onTabUnselected(TabLayout.Tab tab) {

    }

    @Override
    public void onTabReselected(TabLayout.Tab tab) {
        int position = tab.getPosition();
        if(position < this.fragments.length){
            Fragment fragment = this.fragments[position];
            if (fragment instanceof ListFragment) {
                ((ListFragment) fragment).scrollToTop();
            }
        }
    }
}
