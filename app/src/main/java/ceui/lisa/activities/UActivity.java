package ceui.lisa.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.StringAdapter;
import ceui.lisa.base.BaseActivity;
import ceui.lisa.cache.Cache;
import ceui.lisa.databinding.ActivityUserNewBinding;
import ceui.lisa.databinding.TagItemBinding;
import ceui.lisa.fragments.FragmentRecmdIllust;
import ceui.lisa.fragments.FragmentUserIllust;
import ceui.lisa.fragments.FragmentUserInfo;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Display;
import ceui.lisa.models.UserDetailResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class UActivity extends BaseActivity<ActivityUserNewBinding> implements Display<UserDetailResponse> {

//    private int userID = 465084;
    private int userID = 34234422;

    @Override
    protected int initLayout() {
        return R.layout.activity_user_new;
    }

    @Override
    protected void initView() {
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
    }

    @Override
    protected void initData() {
        UserDetailResponse user = Cache.get().getModel("UActivity Model " + userID, UserDetailResponse.class);
        if (user != null) {
            Common.showToast("使用本地的");
            invoke(user);
        } else {
            Common.showToast("使用远端的");
            getUserDetail();
        }
    }

    private void getUserDetail() {
        Retro.getAppApi().getUserDetail(Shaft.sUserModel.getResponse().getAccess_token(), userID)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserDetailResponse>() {
                    @Override
                    public void success(UserDetailResponse userDetailResponse) {
                        Cache.get().saveModel("UActivity Model " + userID, userDetailResponse);
                        invoke(userDetailResponse);
                    }
                });
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    public void invoke(UserDetailResponse data) {
        List<String> content = new ArrayList<>();
        if (data.getProfile().getTotal_illusts() > 0) {
            content.add("插画作品：" + data.getProfile().getTotal_illusts());
        }
        if (data.getProfile().getTotal_manga() > 0) {
            content.add("漫画作品：" + data.getProfile().getTotal_manga());
        }
        if (data.getProfile().getTotal_illust_series() > 0) {
            content.add("漫画系列：" + data.getProfile().getTotal_illust_series());
        }
        if (data.getProfile().getTotal_novels() > 0) {
            content.add("小说作品：" + data.getProfile().getTotal_novels());
        }
        if (data.getProfile().getTotal_novel_series() > 0) {
            content.add("小说系列：" + data.getProfile().getTotal_novel_series());
        }
        if (data.getProfile().getTotal_illust_bookmarks_public() > 0) {
            content.add("插画/漫画收藏：" + data.getProfile().getTotal_illust_bookmarks_public());
        }
        content.add("小说收藏");
        baseBind.tagLayout.setAdapter(new TagAdapter<String>(content) {
            @Override
            public View getView(FlowLayout parent, int position, String s) {
                TagItemBinding binding = DataBindingUtil.inflate(
                        LayoutInflater.from(mContext), R.layout.tag_item, null, false);
                binding.tagName.setText(s);
                return binding.getRoot();
            }
        });
        baseBind.tagLayout.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列作品");
                intent.putExtra(Params.USER_ID, data.getUser().getId());
                startActivity(intent);
                return false;
            }
        });

        if (!TextUtils.isEmpty(data.getUser().getComment())) {
            baseBind.comment.setVisibility(View.VISIBLE);
            baseBind.comment.setText(data.getUser().getComment());
        } else {
            baseBind.comment.setVisibility(View.GONE);
        }
        if (data.getUser().isIs_premium()) {
            baseBind.vipImage.setVisibility(View.VISIBLE);
        } else {
            baseBind.vipImage.setVisibility(View.GONE);
        }
        baseBind.userName.setText(data.getUser().getName());
        baseBind.follow.setText(String.valueOf(data.getProfile().getTotal_follow_users()));
        baseBind.pFriend.setText(String.valueOf(data.getProfile().getTotal_mypixiv_users()));
        baseBind.showDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "详细信息");
                intent.putExtra(Params.CONTENT, data);
                startActivity(intent);
            }
        });


        baseBind.contentItem.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Common.showLog(className + " height " + baseBind.contentItem.getHeight());
                Common.showLog(className + " paddBottom " + baseBind.contentItem.getPaddingBottom());

                ViewGroup.LayoutParams params = baseBind.recyList.getLayoutParams();
                params.height = baseBind.contentItem.getHeight() - baseBind.contentItem.getPaddingBottom() - baseBind.pleaseLl.getHeight();
                baseBind.recyList.setLayoutParams(params);

                baseBind.contentItem.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        List<String> temp = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            temp.add("我是第" + (i + 1) + "条数据啦啦啦啦");
        }
        StringAdapter adapter = new StringAdapter(temp, mContext);
        baseBind.recyList.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyList.setAdapter(adapter);
        baseBind.smartRefreshLayout.setEnableRefresh(false);

    }
}
