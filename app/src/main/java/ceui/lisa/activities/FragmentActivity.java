package ceui.lisa.activities;


import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import ceui.lisa.R;

/**
 * class name: FragmentActivity.class
 * <p>
 * description:
 * author: @CeuiLiSA
 * e-mail: fatemercis@qq.com
 * website: https://github.com/CeuiLiSA
 * created at: 2019/3/24 8:54 PM
 */
public abstract class FragmentActivity extends BaseActivity {

    protected Fragment childFragment;

    @Override
    protected int initLayout() {
        return mLayoutID = R.layout.activity_fragment;
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    protected void initView() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment == null) {
            fragment = createNewFragment();
            if (fragment != null) {
                fragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment)
                        .commit();
                childFragment = fragment;
            }
        }
    }

    protected abstract Fragment createNewFragment();

    @Override
    protected void initData() {

    }
}

