package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.bumptech.glide.Glide;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.tabs.TabLayout;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentAboutUser;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.http.Rx;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.AppBarStateChangeListener;
import de.hdodenhof.circleimageview.CircleImageView;

import static ceui.lisa.activities.Shaft.sUserModel;

public class UserDetailActivity extends BaseActivity {

    private static final String[] TITLES = new String[]{"收藏", "作品", "关于"};
    private int userID;
    private ImageView background;
    private TabLayout mTabLayout;
    private CircleImageView userHead;
    private TextView userName, follow, fans, nowFollow;
    private ViewPager mViewPager;
    private UserDetailResponse mUserDetailResponse;
    private Fragment[] baseFragments;

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
        fans = findViewById(R.id.user_follow);
        Toolbar toolbar = findViewById(R.id.toolbar_help);
        toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        toolbar.setNavigationOnClickListener(v -> finish());
        follow = findViewById(R.id.follow_user);
        nowFollow = findViewById(R.id.follow);
        CollapsingToolbarLayout collapsingToolbarLayout = findViewById(R.id.toolbar_layout);
        AppBarLayout appBarLayout = findViewById(R.id.app_bar);
        appBarLayout.addOnOffsetChangedListener(new AppBarStateChangeListener() {
            @Override
            public void onStateChanged(AppBarLayout appBarLayout, State state) {
                if (state == State.EXPANDED) {

                } else if (state == State.COLLAPSED) {
                    if (mUserDetailResponse != null) {
                        toolbar.setTitle(mUserDetailResponse.getUser().getName());
                    }
                } else {
                    toolbar.setTitle(" ");
                }
            }
        });
        mViewPager = findViewById(R.id.view_pager);
        mViewPager.setPageTransformer(true, new DrawerTransformer());
        mTabLayout = findViewById(R.id.tab);
        follow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra("user id", userID);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "正在关注");
                startActivity(intent);
            }
        });
        fans.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra("user id", userID);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "好P友");
                startActivity(intent);
            }
        });
    }

    @Override
    public void initData() {
        userID = getIntent().getIntExtra("user id", 0);
        baseFragments = new Fragment[]{
                FragmentLikeIllust.newInstance(userID, FragmentLikeIllust.TYPE_PUBLUC),
                FragmentUserIllust.newInstance(userID),
                new FragmentAboutUser()};
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
        mViewPager.setOffscreenPageLimit(baseFragments.length);
        mTabLayout.setupWithViewPager(mViewPager);
        getUserDetail();

    }

    private void getUserDetail() {
        Retro.getAppApi().getUserDetail(sUserModel.getResponse().getAccess_token(), userID)
                .compose(Rx.newThread())
                .subscribe(new ErrorCtrl<UserDetailResponse>() {
                    @Override
                    public void onNext(UserDetailResponse userDetailResponse) {
                        mUserDetailResponse = userDetailResponse;
                        setData(userDetailResponse);
                    }
                });
    }

    private void setData(UserDetailResponse userDetailResponse) {
        if (mContext != null && !isDestroyed()) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(
                            userDetailResponse.getUser()
                                    .getProfile_image_urls().getMedium()))
                    .into(userHead);
            userName.setText(userDetailResponse.getUser().getName());
            if (userDetailResponse.getUser().getId() == sUserModel.getResponse().getUser().getId()) {
                nowFollow.setVisibility(View.GONE);
            }
            if (userDetailResponse.getUser().isIs_followed()) {
                nowFollow.setText("取消關注");
                nowFollow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        nowFollow.setText("+ 關注");
                        PixivOperate.postUnFollowUser(userDetailResponse.getUser().getId());
                    }
                });
            } else {
                nowFollow.setText("+ 關注");
                nowFollow.setOnClickListener(v -> {
                    nowFollow.setText("取消關注");
                    PixivOperate.postFollowUser(userDetailResponse.getUser().getId(), "public");
                });
                nowFollow.setOnLongClickListener(v -> {
                    nowFollow.setText("取消關注");
                    PixivOperate.postFollowUser(userDetailResponse.getUser().getId(), "private");
                    return true;
                });
            }
            fans.setText("好P友：" + userDetailResponse.getProfile().getTotal_mypixiv_users());
            follow.setText("關注：" + userDetailResponse.getProfile().getTotal_follow_users());
            ((FragmentAboutUser) baseFragments[2]).setData(userDetailResponse);
        }
    }
}
