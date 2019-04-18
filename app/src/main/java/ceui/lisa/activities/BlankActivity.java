package ceui.lisa.activities;

import android.graphics.Color;
import android.view.View;

import ceui.lisa.fragments.FragmentRecmd;

public class BlankActivity extends FragmentActivity<FragmentRecmd> {


    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        super.initLayout();
    }

    @Override
    protected FragmentRecmd createNewFragment() {
        //return new FragmentRecmd();
        return null;
    }

    @Override
    protected void initData() {

    }
}
