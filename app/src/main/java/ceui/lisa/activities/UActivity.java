package ceui.lisa.activities;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;

import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.ActicityUserBinding;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.http.Rx;
import ceui.lisa.model.UserDetailResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static org.xmlpull.v1.XmlPullParser.TYPES;

public class UActivity extends BaseActivity<ActicityUserBinding> {

    private int userID;

    @Override
    protected int initLayout() {
        return R.layout.acticity_user;
    }

    @Override
    protected void initView() {
        baseBind.toolbar.setNavigationOnClickListener(view -> finish());
    }

    @Override
    protected void initData() {
        userID = getIntent().getIntExtra("user id", 0);
        Retro.getAppApi().getUserDetail(sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<UserDetailResponse>() {
                    @Override
                    public void onNext(UserDetailResponse userDetailResponse) {
                        Glide.with(mContext).load(GlideUtil.getMediumImg(userDetailResponse
                                .getUser().getProfile_image_urls().getMedium()))
                                .placeholder(R.color.light_bg).into(baseBind.userHead);

                        baseBind.userName.setText(userDetailResponse.getUser().getName());
                        baseBind.userAddress.setText(Common.checkEmpty(userDetailResponse.getProfile().getRegion()));
                        List<String> tagList = userDetailResponse.getTag();
                        if(tagList.size() == 0){
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

                        if (!TextUtils.isEmpty(userDetailResponse.getWorkspace().getWorkspace_image_url())) {
                            Glide.with(mContext)
                                    .load(GlideUtil.getMediumImg(userDetailResponse.getWorkspace().getWorkspace_image_url()))
                                    .transition(withCrossFade())
                                    .into(baseBind.userBackground);
                        }

                        if(userDetailResponse.getProfile().getTotal_illust_bookmarks_public() > 0){
                            baseBind.card1.setVisibility(View.VISIBLE);
                            baseBind.howMany1.setText(String.format("%s个插画/漫画收藏",
                                    String.valueOf(userDetailResponse.getProfile().getTotal_illust_bookmarks_public())));
                        }else {
                            baseBind.card1.setVisibility(View.GONE);
                        }

                        if(userDetailResponse.getProfile().getTotal_illusts() > 0){
                            baseBind.card2.setVisibility(View.VISIBLE);
                            baseBind.howMany2.setText(String.format("%s件插画作品",
                                    String.valueOf(userDetailResponse.getProfile().getTotal_illusts())));
                        }else {
                            baseBind.card2.setVisibility(View.GONE);
                        }

                        if(userDetailResponse.getProfile().getTotal_illust_series() > 0){
                            baseBind.card3.setVisibility(View.VISIBLE);
                            baseBind.howMany3.setText(String.format("%s个插画系列",
                                    String.valueOf(userDetailResponse.getProfile().getTotal_illust_series())));
                        }else {
                            baseBind.card3.setVisibility(View.GONE);
                        }

                        if(userDetailResponse.getProfile().getTotal_manga() > 0){
                            baseBind.card4.setVisibility(View.VISIBLE);
                            baseBind.howMany4.setText(String.format("%s件漫画作品",
                                    String.valueOf(userDetailResponse.getProfile().getTotal_manga())));
                            baseBind.card4.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                }
                            });
                        }else {
                            baseBind.card4.setVisibility(View.GONE);
                        }
                    }
                });
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
