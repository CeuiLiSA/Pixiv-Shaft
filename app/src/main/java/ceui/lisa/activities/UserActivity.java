package ceui.lisa.activities;

import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.ActicityUserBinding;
import ceui.lisa.fragments.FragmentLikeIllustHorizontal;
import ceui.lisa.fragments.FragmentLikeNovelHorizontal;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.UserViewModel;
import ceui.loxia.ObjectPool;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

/**
 * 用户详情页面
 */
public class UserActivity extends BaseActivity<ActicityUserBinding> implements Display<UserDetailResponse> {


    private UserViewModel mUserViewModel;
    @Override
    protected int initLayout() {
        return R.layout.acticity_user;
    }

    @Override
    protected void initView() {
        baseBind.toolbar.setNavigationOnClickListener(view -> finish());
        baseBind.send.hide();
    }

    @Override
    protected void initData() {
        int userID = getIntent().getIntExtra(Params.USER_ID, 0);
        if (Shaft.sSettings.isUseNewUserPage()) {
            Intent intent = new Intent(mContext, UActivity.class);
            intent.putExtra(Params.USER_ID, userID);
            startActivity(intent);
            finish();
            return;
        }
        mUserViewModel = new ViewModelProvider(this).get(UserViewModel.class);
        mUserViewModel.getUser().observe(this, new Observer<UserDetailResponse>() {
            @Override
            public void onChanged(UserDetailResponse userDetailResponse) {
                invoke(userDetailResponse);
            }
        });
        Retro.getAppApi().getUserDetail(sUserModel.getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<UserDetailResponse>() {
                    @Override
                    public void next(UserDetailResponse userResponse) {
                        mUserViewModel.getUser().setValue(userResponse);
                    }
                });
        baseBind.turnGray.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                gray(isChecked);
            }
        });


    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    public void invoke(UserDetailResponse currentUser) {
        Glide.with(mContext).load(GlideUtil.getUrl(currentUser
                .getUser().getProfile_image_urls().getMaxImage()))
                .placeholder(R.color.light_bg).into(baseBind.userHead);
        baseBind.userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(Params.URL, currentUser.getUser().getProfile_image_urls().getMaxImage());
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                mContext.startActivity(intent);
            }
        });
        baseBind.userName.setText(currentUser.getUser().getName());
        baseBind.userAddress.setText(Common.checkEmpty(currentUser.getProfile().getRegion()));
        baseBind.userAddress.setVisibility(View.VISIBLE);
        List<String> tagList = new ArrayList<>();
        tagList.add(getString(R.string.string_235) + ": " + currentUser.getProfile().getTotal_mypixiv_users());
        tagList.add(getString(R.string.string_145) + currentUser.getProfile().getTotal_follow_users());
        tagList.add(getString(R.string.string_146));
        baseBind.tagType.setAdapter(new TagAdapter<String>(tagList) {
            @Override
            public View getView(FlowLayout parent, int position, String s) {
                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_tag_text,
                        parent, false);
                tv.setText(s);
                return tv;
            }
        });
        baseBind.tagType.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                if (position == 0) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.USER_ID, currentUser.getUser().getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "好P友");
                    startActivity(intent);
                } else if (position == 1) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.USER_ID, currentUser.getUser().getId());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "正在关注");
                    startActivity(intent);
                } else if (position == 2) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "详细信息");
                    intent.putExtra(Params.CONTENT, currentUser);
                    startActivity(intent);
                }
                return false;
            }
        });


        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();

        if (currentUser.getUser().getId() != sUserModel.getUser().getId()) {
            //如果看的是自己的主页，先展示收藏
            //如果看的是别人的主页，先展示作品
            if (currentUser.getProfile().getTotal_illusts() > 0) {
                transaction.replace(R.id.container1, FragmentLikeIllustHorizontal.
                        newInstance(currentUser, 2));// 1插画收藏    2插画作品     3漫画作品
            }

            if (currentUser.getProfile().getTotal_manga() > 0) {
                transaction.replace(R.id.container2, FragmentLikeIllustHorizontal.
                        newInstance(currentUser, 3));// 1插画收藏    2插画作品     3漫画作品
            }

            if (currentUser.getProfile().getTotal_illust_bookmarks_public() > 0) {
                transaction.replace(R.id.container3, FragmentLikeIllustHorizontal.
                        newInstance(currentUser, 1));// 1插画收藏    2插画作品     3漫画作品
            }

            if (currentUser.getUser().isIs_followed()) {
                baseBind.send.setImageResource(R.drawable.ic_favorite_accent_24dp);
            } else {
                baseBind.send.setImageResource(R.drawable.ic_favorite_black_24dp);
            }

            baseBind.send.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (currentUser.getUser().isIs_followed()) {
                        baseBind.send.setImageResource(R.drawable.ic_favorite_black_24dp);
                        currentUser.getUser().setIs_followed(false);
                        PixivOperate.postUnFollowUser(currentUser.getUser().getId());
                    } else {
                        baseBind.send.setImageResource(R.drawable.ic_favorite_accent_24dp);
                        currentUser.getUser().setIs_followed(true);
                        PixivOperate.postFollowUser(currentUser.getUser().getId(),
                                Params.TYPE_PUBLIC);
                    }
                }
            });
            baseBind.send.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    if (!currentUser.getUser().isIs_followed()) {
                        baseBind.send.setImageResource(R.drawable.ic_favorite_accent_24dp);
                        currentUser.getUser().setIs_followed(true);
                        PixivOperate.postFollowUser(currentUser.getUser().getId(),
                                Params.TYPE_PRIVATE);
                    }
                    return true;
                }
            });
            baseBind.send.show();
        } else {
            //如果看的是自己的主页，先展示收藏
            //如果看的是别人的主页，先展示作品
            if (currentUser.getProfile().getTotal_illust_bookmarks_public() > 0) {
                transaction.replace(R.id.container1,
                        FragmentLikeIllustHorizontal.newInstance(currentUser, 1));// 1插画收藏    2插画作品     3漫画作品
            }

            if (currentUser.getProfile().getTotal_illusts() > 0) {
                transaction.replace(R.id.container2,
                        FragmentLikeIllustHorizontal.newInstance(currentUser, 2));// 1插画收藏    2插画作品     3漫画作品
            }

            if (currentUser.getProfile().getTotal_manga() > 0) {
                transaction.replace(R.id.container3,
                        FragmentLikeIllustHorizontal.newInstance(currentUser, 3));// 1插画收藏    2插画作品     3漫画作品
            }
        }

        if (currentUser.getProfile().getTotal_novels() > 0) {
            transaction.replace(R.id.container4,
                    FragmentLikeNovelHorizontal.newInstance(1, currentUser.getUserId(),
                            currentUser.getProfile().getTotal_novels()));// 0收藏的小说， 1创作的小说
        }

        transaction.replace(R.id.container5,
                FragmentLikeNovelHorizontal.newInstance(0, currentUser.getUserId(),
                        currentUser.getProfile().getTotal_novels()));// 0收藏的小说， 1创作的小说

        transaction.commitNowAllowingStateLoss();

        if (!TextUtils.isEmpty(currentUser.getWorkspace().getWorkspace_image_url())) {
            Glide.with(mContext)
                    .load(GlideUtil.getUrl(currentUser.getWorkspace().getWorkspace_image_url()))
                    .transition(withCrossFade())
                    .into(baseBind.userBackground);

            baseBind.userBackground.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.URL, currentUser.getWorkspace().getWorkspace_image_url());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                    mContext.startActivity(intent);
                }
            });
        }
    }
}
