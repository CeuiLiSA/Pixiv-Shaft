package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.utils.AppBarStateChangeListener;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class UserDetailActivity extends BaseActivity {

    private static final String[] TITLES = new String[]{"收藏", "作品", "关于"};
    private int userID;
    private ImageView background;
    private TabLayout mTabLayout;
    private CircleImageView userHead;
    private TextView userName, follow, fans, nowFollow;
    private ViewPager mViewPager;


    @Override
    public void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_user_detail;
    }

    @Override
    public void initView() {
        background = findViewById(R.id.user_background);
        userHead = findViewById(R.id.user_head);
        userName = findViewById(R.id.user_name);
        follow = findViewById(R.id.user_follow);
        Toolbar toolbar = findViewById(R.id.toolbar_help);
        toolbar.setNavigationOnClickListener(v -> finish());
        fans = findViewById(R.id.follow_user);
        nowFollow = findViewById(R.id.follow);
        nowFollow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        CollapsingToolbarLayout collapsingToolbarLayout = findViewById(R.id.toolbar_layout);
        AppBarLayout appBarLayout = findViewById(R.id.app_bar);
        appBarLayout.addOnOffsetChangedListener(new AppBarStateChangeListener() {
            @Override
            public void onStateChanged(AppBarLayout appBarLayout, State state) {
                if (state == State.EXPANDED) {

                } else if (state == State.COLLAPSED) {

                } else {
                    toolbar.setTitle(" ");
                }
            }
        });
        mViewPager = findViewById(R.id.view_pager);
        mTabLayout = findViewById(R.id.tab);
        follow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });
        fans.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
    }

    @Override
    public void initData() {
        userID = getIntent().getIntExtra("user id", 0);
        getUserDetail();
    }

    private void getUserDetail() {

    }


}
