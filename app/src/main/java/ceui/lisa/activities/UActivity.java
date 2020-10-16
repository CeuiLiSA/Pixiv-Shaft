package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.github.ybq.android.spinkit.style.Wave;
import com.google.android.material.appbar.AppBarLayout;

import ceui.lisa.R;
import ceui.lisa.base.BaseActivity;
import ceui.lisa.databinding.ActivityNewUserBinding;
import ceui.lisa.fragments.FragmentHolder;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.UserViewModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class UActivity extends BaseActivity<ActivityNewUserBinding> implements Display<UserDetailResponse> {

    private int userID;
    private UserViewModel mUserViewModel;

    @Override
    protected int initLayout() {
        return R.layout.activity_new_user;
    }

    @Override
    protected void initView() {
        Wave wave = new Wave();
        baseBind.progress.setIndeterminateDrawable(wave);
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
        baseBind.toolbarLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int offset = baseBind.toolbarLayout.getHeight() - Shaft.statusHeight - Shaft.toolbarHeight;
                baseBind.appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
                    @Override
                    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                        if (Math.abs(verticalOffset) < 15) {
                            baseBind.centerHeader.setAlpha(1.0f);
                            baseBind.toolbarTitle.setAlpha(0.0f);
                        } else if ((offset - Math.abs(verticalOffset)) < 15) {
                            baseBind.centerHeader.setAlpha(0.0f);
                            baseBind.toolbarTitle.setAlpha(1.0f);
                        } else {
                            baseBind.centerHeader.setAlpha(1 + (float) verticalOffset / offset);
                            baseBind.toolbarTitle.setAlpha(-(float) verticalOffset / offset);
                        }
                        Common.showLog(className + verticalOffset);
                    }
                });
                baseBind.toolbarLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    @Override
    protected void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
    }

    @Override
    public void initModel() {
        mUserViewModel = new ViewModelProvider(this).get(UserViewModel.class);
        mUserViewModel.getUser().observe(this, new Observer<UserDetailResponse>() {
            @Override
            public void onChanged(UserDetailResponse userDetailResponse) {
                invoke(userDetailResponse);
            }
        });
    }

    @Override
    protected void initData() {
        baseBind.progress.setVisibility(View.VISIBLE);
        Retro.getAppApi().getUserDetail(Shaft.sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserDetailResponse>() {
                    @Override
                    public void success(UserDetailResponse user) {
                        mUserViewModel.getUser().setValue(user);
                    }

                    @Override
                    public void must(boolean isSuccess) {
                        super.must(isSuccess);
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }
                });
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    public void invoke(UserDetailResponse data) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, FragmentHolder.newInstance())
                .commitNow();

        if (userID == Shaft.sUserModel.getUserId()) {
            baseBind.starUser.setVisibility(View.INVISIBLE);
        } else {
            baseBind.starUser.setVisibility(View.VISIBLE);
            if (data.getUser().isIs_followed()) {
                baseBind.starUser.setText(R.string.string_177);
            } else {
                baseBind.starUser.setText(R.string.string_178);
            }
            baseBind.starUser.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (data.getUser().isIs_followed()) {
                        baseBind.starUser.setText(R.string.string_178);
                        PixivOperate.postUnFollowUser(data.getUser().getId());
                        data.getUser().setIs_followed(false);
                    } else {
                        baseBind.starUser.setText(R.string.string_177);
                        PixivOperate.postFollowUser(data.getUser().getId(), Params.TYPE_PUBLUC);
                        data.getUser().setIs_followed(true);
                    }
                }
            });
        }

        baseBind.centerHeader.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(0.0f, 1.0f);
        animation.setDuration(800L);
        baseBind.centerHeader.startAnimation(animation);
        if (data.getUser().isIs_premium()) {
            baseBind.vipImage.setVisibility(View.VISIBLE);
        } else {
            baseBind.vipImage.setVisibility(View.GONE);
        }
        Glide.with(mContext).load(GlideUtil.getHead(data.getUser())).into(baseBind.userHead);
        baseBind.userName.setText(data.getUser().getName());
        baseBind.follow.setText(String.valueOf(data.getProfile().getTotal_follow_users()));
        baseBind.pFriend.setText(String.valueOf(data.getProfile().getTotal_mypixiv_users()));

        View.OnClickListener pFriend = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.USER_ID, data.getUser().getId());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "好P友");
                startActivity(intent);
            }
        };
        baseBind.pFriend.setOnClickListener(pFriend);
        baseBind.pFriendS.setOnClickListener(pFriend);

        View.OnClickListener follow = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.USER_ID, data.getUser().getId());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "正在关注");
                startActivity(intent);
            }
        };
        baseBind.follow.setOnClickListener(follow);
        baseBind.followS.setOnClickListener(follow);

    }
}
