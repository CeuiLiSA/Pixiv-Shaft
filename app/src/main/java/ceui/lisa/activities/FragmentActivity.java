package ceui.lisa.activities;


import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;

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
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_fragment;
    }

    @Override
    protected void initView() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment == null) {
            fragment = createNewFragment();
            if(fragment != null) {
                fragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment)
                        .commit();
            }
            childFragment = fragment;
        }
    }

    protected abstract Fragment createNewFragment();

    @Override
    protected void initData() {

    }
}

