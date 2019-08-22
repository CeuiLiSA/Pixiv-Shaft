package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import androidx.annotation.Nullable;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.List;
import java.util.concurrent.TimeUnit;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentAboutUser;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.AppBarStateChangeListener;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class UserDetailActivity extends BaseActivity {

    private static final String[] TITLES = new String[]{"收藏", "作品", "关于"};
    private int userID;
    private ImageView background;
    private TabLayout mTabLayout;
    private CircleImageView userHead;
    private TextView userName, follow, fans, nowFollow;
    private ViewPager mViewPager;
    private UserDetailResponse mUserDetailResponse;
    private boolean active = false;
    private int nowIndex = 0;
    private BaseFragment[] baseFragments;


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
        baseFragments = new BaseFragment[]{
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
        //传进来的id 等于app当前用户的id,直接加载背景图。
        if (userID == sUserModel.getResponse().getUser().getId()) {
            getBackground();
        }
    }

    private void getUserDetail() {
        Retro.getAppApi().getUserDetail(sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<UserDetailResponse>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(UserDetailResponse userDetailResponse) {
                        if (userDetailResponse != null) {
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

    private void getBackground(){
        Retro.getAppApi().getLoginBg(sUserModel.getResponse().getAccess_token())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<ListIllustResponse>() {
                    @Override
                    public void onNext(ListIllustResponse listIllustResponse) {
                        List<IllustsBean> list = listIllustResponse.getList();
                        background.setOnClickListener(v -> {
                            IllustChannel.get().setIllustList(list);
                            Intent intent = new Intent(mContext, ViewPagerActivity.class);
                            intent.putExtra("position", nowIndex);
                            startActivity(intent);
                        });
                        Observable.interval(0, 15, TimeUnit.SECONDS,
                                AndroidSchedulers.mainThread())
                                .takeWhile(aLong -> active)
                                .subscribe(new Observer<Long>() {
                                    @Override
                                    public void onSubscribe(Disposable d) {

                                    }

                                    @Override
                                    public void onNext(Long aLong) {
                                        int index = (int) (aLong % list.size());
                                        nowIndex = index;
                                        //平滑切换背景图
                                        Glide.with(mContext)
                                                .load(GlideUtil.getLargeImage(list.get(index)))
                                                .placeholder(background.getDrawable())
                                                .transition(withCrossFade(1250))
                                                .into(background);
                                        Glide.with(mContext)
                                                .load(GlideUtil.getMediumImg(list.get(++index % list.size())))
                                                .preload();
                                    }

                                    @Override
                                    public void onError(Throwable e) {

                                    }

                                    @Override
                                    public void onComplete() {

                                    }
                                });
                    }
                });
    }


    private void setData(UserDetailResponse userDetailResponse) {
//      when(exception.message){ "You can not start a load for a destroyed activity" -> Glide.with(Shaft.getContext())}
        if(mContext != null && !isDestroyed()) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(
                            userDetailResponse.getUser()
                                    .getProfile_image_urls().getMedium()))
                    .into(userHead);
            userName.setText(userDetailResponse.getUser().getName());
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
            ((FragmentAboutUser)baseFragments[2]).setData(userDetailResponse);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
    }
}
