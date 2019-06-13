package ceui.lisa.activities;

import android.graphics.Color;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentBlank;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentSubmitIllust;
import ceui.lisa.http.Retro;
import ceui.lisa.response.UserDetailResponse;
import ceui.lisa.response.UserModel;
import ceui.lisa.view.AppBarStateChangeListener;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
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
    private UserDetailResponse mUserDetailResponse;


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
        toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
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
                    if(mUserDetailResponse != null){
                        toolbar.setTitle(mUserDetailResponse.getUser().getName());
                    }
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
        BaseFragment[] baseFragments = new BaseFragment[]{
                FragmentLikeIllust.newInstance(userID),
                FragmentSubmitIllust.newInstance(userID),
                new FragmentBlank()};
        mViewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return baseFragments[i];
            }

            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return TITLES[position];
            }
        });
        mTabLayout.setupWithViewPager(mViewPager);
        getUserDetail();
    }

    private void getUserDetail() {
        UserModel userModel = Local.getUser();
        Retro.getAppApi().getUserDetail(userModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<UserDetailResponse>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(UserDetailResponse userDetailResponse) {
                        if(userDetailResponse != null){
                            mUserDetailResponse = userDetailResponse;
                            setData(userDetailResponse);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }


    private void setData(UserDetailResponse userDetailResponse){
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(
                        userDetailResponse.getUser()
                                .getProfile_image_urls().getMedium()))
                .into(userHead);
        userName.setText(userDetailResponse.getUser().getName());
        if(userDetailResponse.getUser().isIs_followed()){
            nowFollow.setText("取消關注");
        }else {
            nowFollow.setText("+ 關注");
        }
        follow.setText("關注：" + userDetailResponse.getProfile().getTotal_mypixiv_users());
        fans.setText("粉絲：" + userDetailResponse.getProfile().getTotal_follow_users());
    }

}
