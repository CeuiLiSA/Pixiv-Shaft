package ceui.lisa.activities;

import android.graphics.Color;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.view.View;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.fragments.FragmentSettings;
import ceui.lisa.fragments.FragmentSign;

public class LoginActivity extends FragmentActivity {

    private FragmentLogin mFragmentLogin;
    private FragmentSign mFragmentSign;
    private SpringSystem springSystem = SpringSystem.create();
    private Spring rotate;


    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_login;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected FragmentLogin createNewFragment() {
        mFragmentLogin = new FragmentLogin();
        return mFragmentLogin;
    }

    @Override
    protected void initData() {
        rotate = springSystem.createSpring();
        rotate.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(15, 8));
        mFragmentSign = new FragmentSign();
    }

    public void showSignFragment(){
        FragmentManager fragmentManager = getSupportFragmentManager();
        if(mFragmentSign.isAdded()){
            fragmentManager.beginTransaction()
                    .hide(mFragmentLogin)
                    .show(mFragmentSign)
                    .commit();
        }else {
            fragmentManager.beginTransaction()
                    .hide(mFragmentLogin)
                    .add(R.id.fragment_container, mFragmentSign)
                    .show(mFragmentSign)
                    .commit();
        }



        rotate.setCurrentValue(0);
        mFragmentSign.getView().setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                mFragmentSign.getView().setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
        //scale.setEndValue(0.5f);
    }

    public void showLoginFragment(){
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
                .hide(mFragmentSign)
                .show(mFragmentLogin)
                .commit();



        rotate.setCurrentValue(0);
        mFragmentLogin.getView().setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                mFragmentLogin.getView().setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }
}
