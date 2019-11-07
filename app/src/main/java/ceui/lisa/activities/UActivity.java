package ceui.lisa.activities;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.ActicityUserBinding;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.fragments.FragmentLikeIllustHorizontal;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class UActivity extends BaseActivity<ActicityUserBinding> {

    private int userID;
    private UserDetailResponse currentUser;

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
        userID = getIntent().getIntExtra("user id", 0);
        Retro.getAppApi().getUserDetail(sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<UserDetailResponse>() {
                    @Override
                    public void onNext(UserDetailResponse user) {
                        currentUser = user;
                        Glide.with(mContext).load(GlideUtil.getMediumImg(currentUser
                                .getUser().getProfile_image_urls().getMedium()))
                                .placeholder(R.color.light_bg).into(baseBind.userHead);

                        baseBind.userName.setText(currentUser.getUser().getName());
                        baseBind.userAddress.setText(Common.checkEmpty(currentUser.getProfile().getRegion()));
                        List<String> tagList = currentUser.getTag();
                        if (tagList.size() == 0) {
                            tagList.add("Pixiv member");
                            tagList.add("にじげん");
                        }
                        baseBind.tagType.setAdapter(new TagAdapter<String>(tagList) {
                            @Override
                            public View getView(FlowLayout parent, int position, String s) {
                                TextView tv = (TextView) LayoutInflater.from(mContext).inflate(R.layout.recy_single_tag_text,
                                        parent, false);
                                tv.setText(s);
                                return tv;
                            }
                        });



                        if(currentUser.getUser().getId() != sUserModel.getResponse().getUser().getId()){
                            if(currentUser.getUser().isIs_followed()){
                                baseBind.send.setImageResource(R.drawable.ic_favorite_accent_24dp);
                            } else {
                                baseBind.send.setImageResource(R.drawable.ic_favorite_black_24dp);
                            }

                            baseBind.send.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if(currentUser.getUser().isIs_followed()){
                                        baseBind.send.setImageResource(R.drawable.ic_favorite_black_24dp);
                                        currentUser.getUser().setIs_followed(false);
                                        PixivOperate.postUnFollowUser(currentUser.getUser().getId());
                                    }else {
                                        baseBind.send.setImageResource(R.drawable.ic_favorite_accent_24dp);
                                        currentUser.getUser().setIs_followed(true);
                                        PixivOperate.postFollowUser(currentUser.getUser().getId(),
                                                FragmentLikeIllust.TYPE_PUBLUC);
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
                                                FragmentLikeIllust.TYPE_PRIVATE);
                                    }
                                    return true;
                                }
                            });
                            baseBind.send.show();
                        }

                        if (!TextUtils.isEmpty(currentUser.getWorkspace().getWorkspace_image_url())) {
                            Glide.with(mContext)
                                    .load(GlideUtil.getMediumImg(currentUser.getWorkspace().getWorkspace_image_url()))
                                    .transition(withCrossFade())
                                    .into(baseBind.userBackground);
                        }

                        if (currentUser.getProfile().getTotal_illust_bookmarks_public() > 0) {
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.illust_collection,
                                            FragmentLikeIllustHorizontal
                                                    .newInstance(currentUser, 1))
                                    .commit();
                        }

                        if (currentUser.getProfile().getTotal_illusts() > 0) {
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.illust_works,
                                            FragmentLikeIllustHorizontal
                                                    .newInstance(currentUser, 2))
                                    .commit();
                        }

                        if (currentUser.getProfile().getTotal_manga() > 0) {
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.manga_works,
                                            FragmentLikeIllustHorizontal
                                                    .newInstance(currentUser, 3))
                                    .commit();
                        }
                    }
                });
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
