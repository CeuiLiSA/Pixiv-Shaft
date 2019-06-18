package ceui.lisa.activities;

import android.graphics.Color;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.CardView;
import android.view.View;
import android.widget.TextView;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.fragments.FragmentSign;

public class LoginAlphaActivity extends BaseActivity {

    private ConstraintLayout cardLogin, cardSign;
    private SpringSystem springSystem = SpringSystem.create();
    private Spring rotate;


    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_login_alpha;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void initView() {
        cardLogin = findViewById(R.id.fragment_login);
        cardSign = findViewById(R.id.fragment_sign);
        TextView showSign = findViewById(R.id.has_no_account);
        showSign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSignCard();
            }
        });
        TextView showLogin = findViewById(R.id.go_to_login);
        showLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLoginCard();
            }
        });
    }


    @Override
    protected void initData() {
        rotate = springSystem.createSpring();
        rotate.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(15, 8));
    }

    public void showSignCard(){
        cardLogin.setVisibility(View.INVISIBLE);
        cardSign.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        cardSign.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                cardSign.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }

    public void showLoginCard(){
        cardSign.setVisibility(View.INVISIBLE);
        cardLogin.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        cardLogin.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                cardLogin.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }
}
