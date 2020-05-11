package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.view.View;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivitySplashBinding;


public class SplashActivity extends BaseActivity<ActivitySplashBinding> {

    @Override
    protected int initLayout() {
        return R.layout.activity_splash;
    }

    @Override
    protected void initView() {

    }

    @Override
    protected void initData() {
        Intent intent = new Intent(mContext, MainActivity.class);
        MainActivity.newInstance(intent, mContext);
        finish();
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
