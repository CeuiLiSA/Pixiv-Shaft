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
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.MuteEntity;
import ceui.lisa.databinding.ActivityNewUserBinding;
import ceui.lisa.fragments.FragmentHolder;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.models.UserFollowDetail;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.AppLevelViewModel;
import ceui.lisa.viewmodel.UserViewModel;
import ceui.loxia.Event;
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
        final MuteEntity entity = AppDatabase.getAppDatabase(this).searchDao().getUserMuteEntityByID(userID);
        mUserViewModel.isUserMuted.setValue(entity != null);

        final MuteEntity block = AppDatabase.getAppDatabase(this).searchDao().getBlockMuteEntityByID(userID);
        mUserViewModel.isUserBlocked.setValue(block != null);

        Shaft.appViewModel.getFollowUserLiveData(userID).observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                updateFollowUserUI(integer);
            }
        });
    }

    @Override
    protected void initData() {
        baseBind.progress.setVisibility(View.VISIBLE);
        Retro.getAppApi().getUserDetail(Shaft.sUserModel.getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserDetailResponse>() {
                    @Override
                    public void success(UserDetailResponse user) {
                        mUserViewModel.getUser().setValue(user);
                        Shaft.appViewModel.updateFollowUserStatus(userID, user.getUser().isIs_followed() ? AppLevelViewModel.FollowUserStatus.FOLLOWED : AppLevelViewModel.FollowUserStatus.NOT_FOLLOW);
                    }

                    @Override
                    public void must() {
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }
                });
        Retro.getAppApi().getFollowDetail(Shaft.sUserModel.getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserFollowDetail>() {
                    @Override
                    public void success(UserFollowDetail userFollowDetail) {
                        //mUserViewModel.getUserFollowDetail().setValue(userFollowDetail);
                        int followStatus = AppLevelViewModel.FollowUserStatus.NOT_FOLLOW;
                        if (userFollowDetail.isPublicFollow()) {
                            followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PUBLIC;
                        } else if (userFollowDetail.isPrivateFollow()) {
                            followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED_PRIVATE;
                        } else if (userFollowDetail.isFollow()) {
                            followStatus = AppLevelViewModel.FollowUserStatus.FOLLOWED;
                        }
                        Shaft.appViewModel.updateFollowUserStatus(userID, followStatus);
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
                .commitNowAllowingStateLoss();

        if (userID == Shaft.sUserModel.getUserId()) {
            baseBind.starUser.setVisibility(View.INVISIBLE);
            baseBind.moreAction.setVisibility(View.GONE);
        } else {
            baseBind.starUser.setVisibility(View.VISIBLE);
            baseBind.moreAction.setVisibility(View.VISIBLE);
            baseBind.moreAction.setOnClickListener(v -> {
                final boolean isMuted = Boolean.TRUE.equals(mUserViewModel.isUserMuted.getValue());
                String[] OPTIONS = new String[] {
                        isMuted ? getString(R.string.cancel_block_this_users_work) : getString(R.string.block_this_users_work),
//                        getString(R.string.add_user_to_blacklist)
                };
                new QMUIDialog.MenuDialogBuilder(mActivity)
                        .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
                        .addItems(OPTIONS, (dialog, which) -> {
                            if (which == 0) {
                                if (isMuted) {
                                    PixivOperate.unMuteUser(data.getUser());
                                    mUserViewModel.isUserMuted.setValue(false);
                                } else  {
                                    PixivOperate.muteUser(data.getUser());
                                    mUserViewModel.isUserMuted.setValue(true);
                                }
                                mUserViewModel.refreshEvent.setValue(new Event<>(100, 0L));
                            } else if (which == 1) {

                            }
                            dialog.dismiss();
                        })
                        .show();
            });

            baseBind.starUser.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Integer integerValue = Shaft.appViewModel.getFollowUserLiveData(userID).getValue();
                    if (AppLevelViewModel.FollowUserStatus.isFollowed(integerValue)) {
                        PixivOperate.postUnFollowUser(data.getUser().getId());
                        data.getUser().setIs_followed(false);
                    } else {
                        PixivOperate.postFollowUser(data.getUser().getId(), Params.TYPE_PUBLIC);
                        data.getUser().setIs_followed(true);
                    }
                }
            });
            baseBind.starUser.setOnLongClickListener(v1 -> {
                Integer integerValue = Shaft.appViewModel.getFollowUserLiveData(userID).getValue();
                if (!AppLevelViewModel.FollowUserStatus.isFollowed(integerValue)) {
                    data.getUser().setIs_followed(true);
                }
                PixivOperate.postFollowUser(data.getUser().getId(), Params.TYPE_PRIVATE);
                return true;
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
        baseBind.userName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.copy(mContext, String.valueOf(data.getUser().getId()));
            }
        });
        baseBind.userName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Common.copy(mContext, data.getUser().getName());
                return true;
            }
        });

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

    private void updateFollowUserUI(int status) {
        if (AppLevelViewModel.FollowUserStatus.isFollowed(status)) {
            baseBind.starUser.setText(R.string.string_177);
            if (AppLevelViewModel.FollowUserStatus.isPrivateFollowed(status)) {
                baseBind.starUser.setBackgroundResource(R.drawable.follow_button_stroke_new_dotted);
            } else {
                baseBind.starUser.setBackgroundResource(R.drawable.follow_button_stroke_new);
            }
        } else {
            baseBind.starUser.setText(R.string.string_178);
            baseBind.starUser.setBackgroundResource(R.drawable.follow_button_stroke_new);
        }
    }
}
