package ceui.lisa.activities;

import android.graphics.Color;
import android.view.View;

import ceui.lisa.fragments.FragmentLogin;

public class LoginActivity extends FragmentActivity {

    @Override
    protected void initLayout() {
        super.initLayout();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected FragmentLogin createNewFragment() {
        return new FragmentLogin();
    }
}
